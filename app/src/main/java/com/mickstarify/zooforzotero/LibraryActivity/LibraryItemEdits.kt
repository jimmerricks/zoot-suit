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
 * @param api the API client, built on first use (see LibraryActivityModel)
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
            notNew = { uploadNoteEdit(note) }) { ui.makeToastAlert("Note saved.") }
    }

    private fun uploadNoteEdit(note: Note) {
        api().modifyNote(note, zoteroDB.getLibraryVersion())
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : CompletableObserver {
                override fun onComplete() {
                    ui.hideBasicSyncAnimation()
                    syncItems()
                    ui.makeToastAlert("Note saved.")
                }

                override fun onSubscribe(d: Disposable) {
                    ui.showBasicSyncAnimation()
                }

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
                    ui.hideBasicSyncAnimation()
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
