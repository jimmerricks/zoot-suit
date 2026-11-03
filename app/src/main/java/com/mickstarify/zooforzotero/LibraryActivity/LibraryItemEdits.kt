package com.mickstarify.zooforzotero.LibraryActivity

import android.util.Log
import com.mickstarify.zooforzotero.ZoteroAPI.DeleteItemListener
import com.mickstarify.zooforzotero.ZoteroAPI.ItemChangedSinceException
import com.mickstarify.zooforzotero.ZoteroAPI.ItemLockedException
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroAPI
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEdit
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditKind
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditStore
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditUploader
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEdits
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.CompletableObserver
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers

private const val TAG = "LibraryItemEdits"

/**
 * The library screen's edits of item metadata: tags and new notes, recorded on the device
 * at once and uploaded with the next sync ([uploadItemEdits]); and edits and deletions of
 * notes, which go to Zotero at once unless the note is new. My Library only.
 *
 * @param api the API client, built on first use (see LibraryComponents)
 * @param invalidateList the list's database queries must be run again (an edit changed
 *   what they find)
 * @param refreshPendingEdits works out again what waits to sync, then calls its argument
 * @param selectedItem the item open in the item view, if any
 * @param syncItems fetches the open library's changed items from Zotero
 */
class LibraryItemEdits(
    private val ui: LibraryUi,
    private val library: OpenLibrary,
    private val api: () -> ZoteroAPI,
    private val zoteroDatabase: ZoteroDatabase,
    private val itemEdits: ItemEditStore,
    private val invalidateList: () -> Unit,
    private val refreshPendingEdits: (onDone: () -> Unit) -> Unit,
    private val selectedItem: () -> Item?,
    private val syncItems: () -> Unit,
) {
    private val state get() = library.state
    private val zoteroDB get() = library.db

    /**
     * Adds ([add]) or removes [tag] on [item], on this device at once; Zotero gets it with
     * the next sync (refreshLibrary), offline or not. My Library only, like notes.
     */
    fun editTag(item: Item, tag: String, add: Boolean) {
        if (state.isUsingGroup()) {
            ui.makeToastAlert("Tags can be edited only in My Library, not in groups.")
            return
        }
        val edit = ItemEdit(if (add) ItemEditKind.ADD_TAG else ItemEditKind.REMOVE_TAG, tag)
        Single.fromCallable { itemEdits.record(GroupInfo.NO_GROUP_ID, item, edit) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ changed ->
                if (!changed) return@subscribe
                invalidateList() // its search holds the item's tags
                // after the count, so the dialog says whether edits are waiting
                refreshPendingEdits { ui.showEditedItem(item) }
            }, { e ->
                Log.e("zotero", "editing a tag of ${item.itemKey} failed: $e")
                ui.createErrorAlert("Couldn't change the tag", "Details: ${e.message ?: e}") {}
            })
    }

    /**
     * Puts the tags [add] on each of the items [keys] (the list's selection) and takes
     * [remove] off them, on this device at once and in one go; Zotero gets it with the next
     * sync, like one item's.
     */
    fun editTags(keys: List<String>, add: List<String>, remove: List<String>) {
        if (state.isUsingGroup()) {
            ui.makeToastAlert("Tags can be edited only in My Library, not in groups.")
            return
        }
        val edits = add.map { ItemEdit(ItemEditKind.ADD_TAG, it) } + remove.map { ItemEdit(ItemEditKind.REMOVE_TAG, it) }
        recordInBulk(edits, "Changing the tags of ${items(keys.size)}…", { keys }) { changed ->
            bulkTagMessage(changed, keys.size)
        }
    }

    /**
     * Puts ([add]) the items [keys] in collection [collectionKey], or takes them out of it,
     * on this device at once, like a tag. Only top-level items can be in collections, so
     * attachments and notes among them are left out.
     */
    fun editCollection(keys: List<String>, collectionKey: String, collectionName: String, add: Boolean) {
        if (state.isUsingGroup()) {
            ui.makeToastAlert("Collections can be changed only in My Library, not in groups.")
            return
        }
        val kind = if (add) ItemEditKind.ADD_TO_COLLECTION else ItemEditKind.REMOVE_FROM_COLLECTION
        var topLevel = keys.size
        val keysToEdit = {
            val parents = zoteroDatabase.parentsOf(keys)
            keys.filter { parents[it] == null && it in parents }.also { topLevel = it.size }
        }
        val edit = ItemEdit(kind, collectionKey)
        recordInBulk(listOf(edit), if (add) "Adding to the collection…" else "Removing from the collection…", keysToEdit) { changed ->
            bulkCollectionMessage(collectionName, add, changed.getValue(edit).size, topLevel, keys.size)
        }
    }

    /**
     * Puts the items [keys] (the list's selection) in each of the collections [add] and
     * takes them out of each of [remove] (collection keys; [names] by key, for the message),
     * on this device at once and in one go, like tags. Only top-level items can be in
     * collections, so attachments and notes among them are left out.
     */
    fun editCollections(keys: List<String>, add: List<String>, remove: List<String>, names: Map<String, String>) {
        if (state.isUsingGroup()) {
            ui.makeToastAlert("Collections can be changed only in My Library, not in groups.")
            return
        }
        val edits = add.map { ItemEdit(ItemEditKind.ADD_TO_COLLECTION, it) } +
                remove.map { ItemEdit(ItemEditKind.REMOVE_FROM_COLLECTION, it) }
        var topLevel = keys.size
        val keysToEdit = { topLevelOf(keys).also { topLevel = it.size } }
        recordInBulk(edits, "Changing the collections of ${items(keys.size)}…", keysToEdit) { changed ->
            bulkCollectionsMessage(changed, names, topLevel, keys.size)
        }
    }

    /** Those of [keys] that are top-level items (not attachments or notes), still there. Blocking. */
    private fun topLevelOf(keys: List<String>): List<String> {
        val parents = zoteroDatabase.parentsOf(keys)
        return keys.filter { parents[it] == null && it in parents }
    }

    /**
     * The collections the top-level items among [keys] are in, by key, each with how many
     * of them; and how many top-level items there are. On the main thread.
     */
    fun collectionsOf(keys: List<String>, onResult: (topLevel: Int, counts: Map<String, Int>) -> Unit) {
        Single.fromCallable { topLevelOf(keys).let { it.size to zoteroDatabase.collectionCountsOf(it) } }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (topLevel, counts) -> onResult(topLevel, counts) }) { e ->
                Log.e("zotero", "reading the collections of ${keys.size} items failed: $e")
                onResult(keys.size, emptyMap())
            }
    }

    /**
     * Records [edits] for the items [keys] reads (off the main thread), with [progress]
     * showing meanwhile; then says [message] of what each edit changed, and redraws.
     */
    private fun recordInBulk(
        edits: List<ItemEdit>,
        progress: String,
        keys: () -> List<String>,
        message: (Map<ItemEdit, List<String>>) -> String,
    ) {
        ui.showLoadingAlertDialog(progress)
        Single.fromCallable { itemEdits.recordAll(GroupInfo.NO_GROUP_ID, keys(), edits) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ changed ->
                ui.hideLoadingAlertDialog()
                ui.makeToastAlert(message(changed))
                if (changed.values.all { it.isEmpty() }) return@subscribe
                invalidateList()
                val collections = edits.any {
                    it.kind == ItemEditKind.ADD_TO_COLLECTION || it.kind == ItemEditKind.REMOVE_FROM_COLLECTION
                }
                refreshPendingEdits { ui.showEditedItems(collectionsChanged = collections) }
            }, { e ->
                ui.hideLoadingAlertDialog()
                Log.e("zotero", "recording ${edits.size} edits in bulk failed: $e")
                ui.createErrorAlert("Couldn't make the change", "Nothing was changed.\n\nDetails: ${e.message ?: e}") {}
            })
    }

    private fun items(n: Int) = if (n == 1) "1 item" else "%,d items".format(n)

    private fun bulkCollectionMessage(name: String, add: Boolean, changed: Int, topLevel: Int, selected: Int): String {
        val skipped = (selected - topLevel).takeIf { it > 0 }
            ?.let { " ${items(it)} left out: attachments and notes go with their parent." } ?: ""
        return when {
            changed == 0 && add -> "All of them are in “$name” already.$skipped"
            changed == 0 -> "None of them is in “$name” itself.$skipped"
            add -> "Added ${items(changed)} to “$name”.$skipped"
            else -> "Removed ${items(changed)} from “$name”." +
                    if (changed < topLevel) " The others are only in its subcollections." else skipped
        }
    }

    /**
     * What a collections change did, from the keys each edit [changed] among the [topLevel]
     * items of the [selected]: "Added 3 items to “a”. Removed 2 items from “b”.", or that
     * nothing needed changing; and that attachments and notes were left out, if any were.
     */
    private fun bulkCollectionsMessage(changed: Map<ItemEdit, List<String>>, names: Map<String, String>, topLevel: Int, selected: Int): String {
        val skipped = (selected - topLevel).takeIf { it > 0 }
            ?.let { " ${items(it)} left out: attachments and notes go with their parent." } ?: ""
        val done = changed.filterValues { it.isNotEmpty() }
        if (done.isEmpty()) {
            return (if (topLevel == 1) "The item's collections were as chosen already." else "The items' collections were as chosen already.") + skipped
        }
        val lines = done.map { (edit, keys) ->
            val name = names[edit.name] ?: edit.name
            if (edit.kind == ItemEditKind.ADD_TO_COLLECTION) "Added ${items(keys.size)} to \u201c$name\u201d."
            else "Removed ${items(keys.size)} from \u201c$name\u201d."
        }
        return lines.take(3).joinToString(" ") +
                (if (lines.size > 3) " ${lines.size - 3} more collections changed." else "") + skipped
    }

    /**
     * What a tag change did, from the keys each edit [changed] among [selected] items:
     * "Added “a” to 3 items. Removed “b” from 12 items.", or that nothing needed changing.
     */
    private fun bulkTagMessage(changed: Map<ItemEdit, List<String>>, selected: Int): String {
        val done = changed.filterValues { it.isNotEmpty() }
        if (done.isEmpty()) return if (selected == 1) "The item's tags were as chosen already." else "The items' tags were as chosen already."
        val lines = done.map { (edit, keys) ->
            if (edit.kind == ItemEditKind.ADD_TAG) "Added \u201c${edit.name}\u201d to ${items(keys.size)}."
            else "Removed \u201c${edit.name}\u201d from ${items(keys.size)}."
        }
        return lines.take(3).joinToString(" ") + if (lines.size > 3) " ${lines.size - 3} more tags changed." else ""
    }

    /** The tags on the items [keys], each with how many of them carry it, on the main thread. */
    fun tagsOf(keys: List<String>, onResult: (List<Pair<String, Int>>) -> Unit) {
        Single.fromCallable { zoteroDatabase.tagCountsOf(keys) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(onResult) { e ->
                Log.e("zotero", "reading the tags of ${keys.size} items failed: $e")
                onResult(emptyList())
            }
    }

    /**
     * Adds a note with [text] (plain text, as typed) to [item], on this device at once;
     * Zotero gets it with the next sync, like a tag. My Library only.
     */
    fun addNote(item: Item, text: String) {
        if (state.isUsingGroup()) {
            ui.makeToastAlert("Notes can be added only in My Library, not in groups.")
            return
        }
        val html = ItemEdits.noteHtml(text) ?: run {
            ui.makeToastAlert("The note is empty, so it wasn't added.")
            return
        }
        Single.fromCallable { itemEdits.createNote(GroupInfo.NO_GROUP_ID, item, html) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ _ ->
                invalidateList() // "Has notes" and searching notes read the database
                refreshPendingEdits { ui.showEditedItem(item) }
            }, { e ->
                Log.e("zotero", "adding a note to ${item.itemKey} failed: $e")
                ui.createErrorAlert("Couldn't add the note", "Details: ${e.message ?: e}") {}
            })
    }

    /**
     * Applies [change] (an edit or a deletion) to a note made on this device that Zotero
     * doesn't have yet, off the main thread; then [onDone] on the main thread, if it was
     * such a note, or [notNew] if Zotero has it.
     */
    private fun changeNewNote(change: () -> ItemEditStore.NewNoteChange, notNew: () -> Unit, onDone: () -> Unit) {
        Single.fromCallable(change)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ result ->
                when (result) {
                    ItemEditStore.NewNoteChange.DONE -> {
                        invalidateList()
                        refreshPendingEdits { ui.refreshItemView() }
                        onDone()
                    }
                    ItemEditStore.NewNoteChange.NOT_NEW -> notNew()
                    ItemEditStore.NewNoteChange.UPLOADING ->
                        ui.makeToastAlert("The note is being synced. Try again in a moment.")
                }
            }, { e ->
                Log.e("zotero", "changing a new note failed: $e")
                ui.createErrorAlert("Couldn't change the note", "Details: ${e.message ?: e}") {}
            })
    }

    /** Uploads My Library's metadata edits, if there are any, says what went wrong, then [onDone]. */
    fun uploadItemEdits(onDone: () -> Unit) {
        Single.fromCallable {
            if (itemEdits.pending(GroupInfo.NO_GROUP_ID).isEmpty()) ItemEditUploader.Result()
            else ItemEditUploader(api(), itemEdits).uploadAll()
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ result ->
                // offline is said once by the sync that follows
                if (result.hasProblems) {
                    ui.createErrorAlert("Some edits weren't synced", itemEditProblems(result)) {}
                }
                refreshPendingEdits {}
                onDone()
            }, { e ->
                Log.e("zotero", "uploading item edits failed: $e")
                onDone()
            })
    }

    private fun itemEditProblems(result: ItemEditUploader.Result): String = listOfNotNull(
        result.gone.takeIf { it > 0 }?.let {
            if (it == 1) "1 edited item was deleted in Zotero; its edits were dropped."
            else "$it edited items were deleted in Zotero; their edits were dropped."
        },
        result.orphanedNotes.takeIf { it > 0 }?.let {
            if (it == 1) "The item of 1 new note was deleted in Zotero; the note was dropped."
            else "The items of $it new notes were deleted in Zotero; the notes were dropped."
        },
        result.missingCollections.takeIf { it > 0 }?.let {
            if (it == 1) "1 item was to go in a collection that was deleted in Zotero; that change was dropped."
            else "$it items were to go in collections that were deleted in Zotero; those changes were dropped."
        },
        result.conflicts.takeIf { it > 0 }?.let {
            "$it item${if (it == 1) "" else "s"} had a field changed here and in Zotero; " +
                "that change stays on this device."
        },
        result.failures.takeIf { it.isNotEmpty() }?.let { failures ->
            "${failures.size} item${if (failures.size == 1) "" else "s"} couldn't be updated; " +
                "the edits stay on this device and are tried again at the next sync.\n\n" +
                "Details: ${failures.first().message ?: failures.first()}"
        },
    ).joinToString("\n\n")

    fun modifyNote(note: Note) {
        if (state.isUsingGroup()) {
            ui.makeToastAlert("Notes can be edited only in My Library, not in groups.")
            return
        }
        changeNewNote({ itemEdits.editNewNote(GroupInfo.NO_GROUP_ID, note.key, note.note) },
            notNew = { uploadNoteEdit(note) }) {
            showSavedNote(note)
            ui.makeToastAlert("Note saved.")
        }
    }

    /**
     * [note] (an edited copy) is saved: the open item's note gets its text. Only then: a
     * change that failed to save showed as if saved until the item was opened again.
     */
    private fun showSavedNote(note: Note) {
        selectedItem()?.notes?.find { it.key == note.key }?.note = note.note
        ui.refreshItemView()
    }

    private fun uploadNoteEdit(note: Note) {
        api().modifyNote(note)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : CompletableObserver {
                override fun onComplete() {
                    showSavedNote(note)
                    syncItems()
                    ui.makeToastAlert("Note saved.")
                }

                override fun onSubscribe(d: Disposable) {}

                override fun onError(e: Throwable) {
                    if (e is ItemLockedException) {
                        ui.createErrorAlert(
                            "Couldn't save the note",
                            "The note is locked, or your API key can't change it."
                        ) {}
                    } else if (e is ItemChangedSinceException) {
                        ui.createErrorAlert(
                            "Couldn't save the note",
                            "The note changed in Zotero since this device last synced. Pull down " +
                                    "to sync, then edit it again."
                        ) {}

                    } else {
                        ui.createErrorAlert(
                            "Couldn't save the note",
                            "Check your connection and try again.\n\nDetails: ${e.message ?: e}"
                        ) {}
                    }
                }
            })
    }

    /** Deletes [note] from Zotero after asking: the API deletes it for good, not to the trash. */
    fun deleteNote(note: Note) {
        if (state.isUsingGroup()) {
            ui.makeToastAlert("Notes can be deleted only in My Library, not in groups.")
            return
        }
        // version 0: made on this device, Zotero doesn't have it (yet)
        val message = if (note.version == 0) "It hasn't been synced yet, so it's only on this device."
        else "It's deleted from Zotero on all your devices, not moved to the trash. This can't be undone."
        ui.createYesNoPrompt("Delete this note?", message, "Delete", "Cancel", {
            changeNewNote({ itemEdits.deleteNewNote(GroupInfo.NO_GROUP_ID, note.key) },
                notNew = { deleteNoteNow(note) }) {
                selectedItem()?.notes?.removeAll { it.key == note.key }
                ui.makeToastAlert("Note deleted.")
            }
        }, {})
    }

    private fun deleteNoteNow(note: Note) {
        api().deleteItem(note.key, note.version, object : DeleteItemListener {
            override fun success() {
                ui.makeToastAlert("Note deleted.")
                // the list reads the database; the next sync would remove it there too
                zoteroDatabase.deleteItem(note.key)
                    .subscribeOn(Schedulers.io())
                    .subscribe({ invalidateList() }, { Log.e(TAG, "deleting note ${note.key} locally failed: $it") })
                // the open item is a row read before: it still lists the note
                selectedItem()?.notes?.removeAll { it.key == note.key }
                ui.refreshItemView()
            }

            override fun failedItemLocked() {
                ui.createErrorAlert(
                    "Couldn't delete the note",
                    "The note is locked, or your API key can't change it."
                ) {}
            }

            override fun failedItemChangedSince() {
                ui.createErrorAlert(
                    "Couldn't delete the note",
                    "The note changed in Zotero since this device last synced. Pull down to " +
                            "sync, then try again."
                ) {}
            }

            override fun failed(code: Int) {
                ui.createErrorAlert(
                    "Couldn't delete the note",
                    "Zotero answered with an error (HTTP $code). Try again later."
                ) {}
            }

            override fun failedNoConnection(e: Throwable) {
                ui.createErrorAlert(
                    "Couldn't delete the note",
                    "Couldn't reach Zotero. Check your connection and try again."
                ) {}
            }
        })
    }
}
