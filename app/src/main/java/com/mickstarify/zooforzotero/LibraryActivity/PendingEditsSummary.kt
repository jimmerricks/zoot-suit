package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEdit
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditKind

/** An attachment whose file was edited here: its title, and the key of the item it belongs to. */
data class EditedFile(val title: String, val parentKey: String?)

/**
 * "N edits to sync" spelled out, for the dialog behind that button: a line per item with
 * what changed on this device, a new note under the item it belongs to, an edited file
 * under its parent item.
 */
object PendingEditsSummary {
    /**
     * @param itemEdits pending metadata edits by item key (a new note's key for CREATE_NOTE)
     * @param titleOf an item's title by key; null if it isn't in the library
     * @param collectionName a collection's name by key; null if it's gone
     */
    fun lines(
        itemEdits: Map<String, List<ItemEdit>>,
        editedFiles: List<EditedFile>,
        titleOf: (String) -> String?,
        collectionName: (String) -> String?,
    ): List<String> {
        val changes = LinkedHashMap<String, MutableList<String>>()
        fun add(key: String, change: String) = changes.getOrPut(key) { mutableListOf() }.add(change)
        fun collection(key: String) = collectionName(key)?.let { "“$it”" } ?: "a collection"

        for ((key, edits) in itemEdits) {
            for (edit in edits) {
                when (edit.kind) {
                    ItemEditKind.ADD_TAG -> add(key, "tag “${edit.name}” added")
                    ItemEditKind.REMOVE_TAG -> add(key, "tag “${edit.name}” removed")
                    ItemEditKind.SET_FIELD -> add(key, "${edit.name} changed")
                    ItemEditKind.CREATE_NOTE -> add(edit.name, "new note") // under the note's parent
                    ItemEditKind.ADD_TO_COLLECTION -> add(key, "added to ${collection(edit.name)}")
                    ItemEditKind.REMOVE_FROM_COLLECTION -> add(key, "removed from ${collection(edit.name)}")
                }
            }
        }
        val standalone = mutableListOf<String>()
        for (file in editedFiles) {
            val change = "file “${file.title}” edited"
            if (file.parentKey != null) add(file.parentKey, change) else standalone += "${file.title}: file edited"
        }
        return changes.map { (key, list) ->
            "${titleOf(key)?.ifBlank { null } ?: "Untitled"}: ${list.joinToString(", ")}"
        } + standalone
    }
}
