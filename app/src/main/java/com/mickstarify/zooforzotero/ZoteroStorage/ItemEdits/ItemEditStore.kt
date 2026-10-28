package com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemData
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

    /** The pending edits of [group]'s items, by item key, in the order they were made. */
    fun pending(group: Int): Map<String, List<ItemEdit>> =
        dao.forGroup(group).groupBy({ it.itemKey }, { it.toEdit() })
            .mapValues { (_, edits) -> edits.filterNotNull() }
            .filterValues { it.isNotEmpty() }

    fun pendingFor(group: Int, itemKey: String): List<ItemEdit> =
        dao.forItem(group, itemKey).mapNotNull { it.toEdit() }

    /** Keys of [group]'s items with edits waiting to be uploaded. */
    fun pendingItemKeys(group: Int): Set<String> = pending(group).keys

    /** Forgets edits: uploaded, or of an item that is gone. */
    fun remove(edits: List<ItemEdit>) {
        if (edits.isNotEmpty()) dao.delete(edits.map { it.id })
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
            if (!dao.hasItem(key)) continue // gone; the upload finds out and drops the edits
            val tags = dao.tagsOf(key)
            val wantedTags = ItemEdits.applyToTags(tags, edits)
            (tags - wantedTags.toSet()).forEach { dao.deleteTag(key, it) }
            (wantedTags - tags.toSet()).forEach { dao.putTag(ItemTag(key, it)) }

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
