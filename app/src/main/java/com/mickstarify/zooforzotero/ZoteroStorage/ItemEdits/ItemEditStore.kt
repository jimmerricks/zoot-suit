package com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits

import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemCollection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemData
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemTag
import com.mickstarify.zooforzotero.ZoteroStorage.Database.PendingItemEdit
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import java.util.Collections

/**
 * Metadata edits made on this device: stored as PendingItemEdit rows until uploaded
 * (ItemEditUploader), and applied at once to the item, in the database and in memory, so
 * lists, the filter and search show them. A sync that writes Zotero's copy of an item over
 * the local one is followed by [reapply]. Blocking; call off the main thread.
 */
class ItemEditStore(private val database: ZoteroDatabase) {
    private val dao get() = database.db.pendingItemEditDao()

    /** Items whose edits are being uploaded; edits recorded meanwhile are only appended. */
    private val uploading: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /**
     * Records [edit] of [item] (of library [group]) and applies it. False if it changed
     * nothing (the tag was there already, say). A field edit needs [Item.hasAllFields].
     */
    fun record(group: Int, item: Item, edit: ItemEdit): Boolean = database.db.runInTransaction<Boolean> {
        val key = item.itemKey
        val tags = item.tags.map { it.tag }
        val fields = item.data
        val pending = dao.forItem(group, key).mapNotNull { it.toEdit() }
        val recording = ItemEdits.record(pending, edit, tags, fields, fold = key !in uploading)
        if (recording == ItemEdits.Recording.NONE) return@runInTransaction false
        if (recording.delete.isNotEmpty()) dao.delete(recording.delete)
        recording.insert?.let { dao.insert(PendingItemEdit.of(it, key, group)) }

        when (edit.kind) {
            ItemEditKind.ADD_TAG -> dao.putTag(ItemTag(key, edit.name))
            ItemEditKind.REMOVE_TAG -> dao.deleteTag(key, edit.name)
            ItemEditKind.SET_FIELD -> writeField(key, edit.name, edit.value ?: "")
            ItemEditKind.CREATE_NOTE -> throw IllegalArgumentException("a new note is made by createNote")
            ItemEditKind.ADD_TO_COLLECTION, ItemEditKind.REMOVE_FROM_COLLECTION ->
                throw IllegalArgumentException("collections are changed by recordAll")
        }
        database.fillKeys(listOf(key)) // the title, date or tags sorted and searched by may have changed
        val applied = listOf(edit)
        if (edit.kind == ItemEditKind.SET_FIELD) {
            item.replaceData(ItemEdits.applyToFields(fields, applied))
        } else {
            item.tags = ItemEdits.applyToTags(tags, applied).map { ItemTag(key, it) }
        }
        true
    }

    /**
     * Records [edit] (a tag or collection edit) for each of the items [keys] of library
     * [group] and applies it, all in one transaction: a bulk edit is either made whole or
     * not at all. Items gone meanwhile are skipped. Returns the keys it changed (an item
     * that had the tag already isn't). The items' in-memory copies are left alone; reload
     * them.
     */
    fun recordAll(group: Int, keys: Collection<String>, edit: ItemEdit): List<String> =
        database.db.runInTransaction<List<String>> {
            val tag = edit.kind == ItemEditKind.ADD_TAG || edit.kind == ItemEditKind.REMOVE_TAG
            require(tag || edit.kind == ItemEditKind.ADD_TO_COLLECTION || edit.kind == ItemEditKind.REMOVE_FROM_COLLECTION) {
                "only tag and collection edits are made in bulk"
            }
            val changed = ArrayList<String>()
            for (key in keys) {
                if (!dao.hasItem(key)) continue
                val pending = dao.forItem(group, key).mapNotNull { it.toEdit() }
                val recording = ItemEdits.record(
                    pending, edit, if (tag) dao.tagsOf(key) else emptyList(), emptyMap(), fold = key !in uploading,
                    collections = if (tag) emptyList() else dao.collectionsOf(key),
                )
                if (recording == ItemEdits.Recording.NONE) continue
                if (recording.delete.isNotEmpty()) dao.delete(recording.delete)
                recording.insert?.let { dao.insert(PendingItemEdit.of(it, key, group)) }
                when (edit.kind) {
                    ItemEditKind.ADD_TAG -> dao.putTag(ItemTag(key, edit.name))
                    ItemEditKind.REMOVE_TAG -> dao.deleteTag(key, edit.name)
                    ItemEditKind.ADD_TO_COLLECTION -> dao.putCollection(ItemCollection(edit.name, key))
                    else -> dao.deleteCollection(key, edit.name)
                }
                changed += key
            }
            if (tag) database.fillKeys(changed) // searched by their tags
            changed
        }

    /**
     * Adds a note with [html] to [parent] (of library [group]): stored as an item at once,
     * so it shows like any other note, and uploaded with the other edits. Returns it, also
     * added to [parent]'s notes.
     */
    fun createNote(group: Int, parent: Item, html: String): Note = database.db.runInTransaction<Note> {
        var key = ItemEdits.newItemKey()
        while (dao.hasItem(key)) key = ItemEdits.newItemKey()
        val edit = ItemEdit(ItemEditKind.CREATE_NOTE, parent.itemKey, html)
        dao.insert(PendingItemEdit.of(edit, key, group))
        writeNote(group, key, edit)
        Note().apply {
            this.parent = parent.itemKey
            this.key = key
            this.note = html
            this.version = 0
            this.tags = emptyList()
        }.also { parent.notes.add(it) }
    }

    /** What [editNewNote] or [deleteNewNote] did. */
    enum class NewNoteChange {
        /** Done, on this device only: Zotero doesn't have the note yet. */
        DONE,
        /** Not a new note: Zotero has it, so the change goes to Zotero. */
        NOT_NEW,
        /** Being uploaded right now; try again once the sync is done. */
        UPLOADING,
    }

    /** Gives [noteKey], if it is a note made here and not uploaded yet, the text [html]. */
    fun editNewNote(group: Int, noteKey: String, html: String): NewNoteChange =
        database.db.runInTransaction<NewNoteChange> {
            val creation = newNoteEdit(group, noteKey) ?: return@runInTransaction NewNoteChange.NOT_NEW
            if (noteKey in uploading) return@runInTransaction NewNoteChange.UPLOADING
            dao.setValue(creation.id, html)
            writeField(noteKey, "note", html)
            database.fillKeys(listOf(noteKey)) // searched by its text
            NewNoteChange.DONE
        }

    /** Deletes [noteKey], if it is a note made here and not uploaded yet: Zotero never gets it. */
    fun deleteNewNote(group: Int, noteKey: String): NewNoteChange =
        database.db.runInTransaction<NewNoteChange> {
            if (newNoteEdit(group, noteKey) == null) return@runInTransaction NewNoteChange.NOT_NEW
            if (noteKey in uploading) return@runInTransaction NewNoteChange.UPLOADING
            dao.delete(dao.forItem(group, noteKey).map { it.id })
            dao.deleteItem(noteKey)
            NewNoteChange.DONE
        }

    private fun newNoteEdit(group: Int, noteKey: String): ItemEdit? =
        pendingFor(group, noteKey).firstOrNull { it.kind == ItemEditKind.CREATE_NOTE }

    /**
     * A new note as an item, the way a sync would store it from Zotero (version 0: none
     * yet). Through this DAO, not ZoteroDatabase.writeItem: that one's Rx inserts run on
     * Room's own thread and would wait forever for the transaction this runs in.
     */
    private fun writeNote(group: Int, noteKey: String, creation: ItemEdit) {
        dao.putItem(ItemInfo(noteKey, group, 0, false, parent = creation.name, itemType = "note"))
        val data = mapOf(
            "key" to noteKey, "itemType" to "note",
            "parentItem" to creation.name, "note" to (creation.value ?: ""),
        )
        for ((name, value) in data) writeField(noteKey, name, value)
        database.fillKeys(listOf(noteKey)) // searched by its text
    }

    /** The pending edits of [group]'s items, by item key, in the order they were made. */
    fun pending(group: Int): Map<String, List<ItemEdit>> =
        dao.forGroup(group).groupBy({ it.itemKey }, { it.toEdit() })
            .mapValues { (_, edits) -> edits.filterNotNull() }
            .filterValues { it.isNotEmpty() }

    fun pendingFor(group: Int, itemKey: String): List<ItemEdit> =
        dao.forItem(group, itemKey).mapNotNull { it.toEdit() }

    /** Keys of [group]'s items with edits waiting to be uploaded. */
    fun pendingItemKeys(group: Int): Set<String> = pending(group).keys

    /**
     * Keys of the items to show as having edits to sync: [pendingItemKeys], with a new
     * note's parent in place of the note, which isn't listed on its own.
     */
    fun editedItemKeys(group: Int): Set<String> = pending(group).map { (key, edits) ->
        edits.firstOrNull { it.kind == ItemEditKind.CREATE_NOTE }?.name ?: key
    }.toSet()

    /** Forgets edits: uploaded, or of an item that is gone. */
    fun remove(edits: List<ItemEdit>) {
        if (edits.isNotEmpty()) dao.delete(edits.map { it.id })
    }

    /** Forgets new note [noteKey] and its [edits]: its item is gone, so it can't be uploaded. */
    fun dropNewNote(noteKey: String, edits: List<ItemEdit>) = database.db.runInTransaction {
        remove(edits)
        dao.deleteItem(noteKey)
    }

    /**
     * Marks [itemKey]'s edits as being uploaded and returns them. One transaction, like
     * [record]'s: an edit recorded at the same time is either in the list or only appended.
     * Read before marking, a removal could still fold away an add in the list, and the
     * upload would send the tag back.
     */
    fun beginUpload(group: Int, itemKey: String): List<ItemEdit> = database.db.runInTransaction<List<ItemEdit>> {
        uploading.add(itemKey)
        pendingFor(group, itemKey)
    }

    fun endUpload(itemKey: String) {
        uploading.remove(itemKey)
    }

    /**
     * Applies [group]'s pending edits to the stored items again, after a sync has written
     * Zotero's copies (which don't have them yet). Call before the library loads from the
     * database. Applying an edit twice changes nothing.
     */
    fun reapply(group: Int) = database.db.runInTransaction {
        for ((key, edits) in pending(group)) {
            val creation = edits.firstOrNull { it.kind == ItemEditKind.CREATE_NOTE }
            if (creation != null) {
                // only a sync that cleared the library removes it: Zotero doesn't have it
                if (!dao.hasItem(key)) writeNote(group, key, creation)
                continue
            }
            if (!dao.hasItem(key)) continue // gone; the upload finds out and drops the edits
            val tags = dao.tagsOf(key)
            val wantedTags = ItemEdits.applyToTags(tags, edits)
            (tags - wantedTags.toSet()).forEach { dao.deleteTag(key, it) }
            (wantedTags - tags.toSet()).forEach { dao.putTag(ItemTag(key, it)) }

            val collections = dao.collectionsOf(key)
            val wantedCollections = ItemEdits.applyToCollections(collections, edits)
            (collections - wantedCollections.toSet()).forEach { dao.deleteCollection(key, it) }
            (wantedCollections - collections.toSet()).forEach { dao.putCollection(ItemCollection(it, key)) }

            val fields = dao.fieldsOf(key).associate { it.name to it.value }
            val wantedFields = ItemEdits.applyToFields(fields, edits)
            for (name in fields.keys + wantedFields.keys) {
                if (fields[name] != wantedFields[name]) writeField(key, name, wantedFields[name] ?: "")
            }
            database.fillKeys(listOf(key))
        }
    }

    /** Blank fields aren't stored (MIGRATION_7_8). */
    private fun writeField(itemKey: String, name: String, value: String) {
        if (value.isEmpty()) dao.deleteField(itemKey, name)
        else dao.putField(ItemData(itemKey, name, value, "String"))
    }
}
