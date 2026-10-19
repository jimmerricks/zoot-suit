package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item

/**
 * The library list's filter. Every condition that is set must hold (AND): an item must
 * carry all of [tags], and if [onDevice] / [editedHere] are set, have an attachment whose
 * PDF is on this device / has edits waiting to sync. An empty filter lets everything
 * through.
 */
data class LibraryFilter(
    val tags: Set<String> = emptySet(),
    val onDevice: Boolean = false,
    val editedHere: Boolean = false,
) {
    val isActive: Boolean get() = tags.isNotEmpty() || onDevice || editedHere

    val needsDeviceState: Boolean get() = onDevice || editedHere

    /**
     * @param attachmentKeys the item's child attachments' keys
     * @param onDeviceKeys attachment keys whose file is on this device
     * @param editedKeys attachment keys whose file has edits waiting to sync
     */
    fun matches(
        item: Item,
        attachmentKeys: (Item) -> Collection<String>,
        onDeviceKeys: Set<String>,
        editedKeys: Set<String>,
    ): Boolean {
        if (tags.isNotEmpty() && !item.tags.map { it.tag }.toSet().containsAll(tags)) return false
        if (!needsDeviceState) return true
        val keys = attachmentKeys(item)
        if (onDevice && keys.none { it in onDeviceKeys }) return false
        if (editedHere && keys.none { it in editedKeys }) return false
        return true
    }

    /** Short description for the toolbar, e.g. "to-read, method · on this device". */
    fun describe(): String = (tags.sortedWith(String.CASE_INSENSITIVE_ORDER) +
            listOfNotNull(
                "on this device".takeIf { onDevice },
                "edits to sync".takeIf { editedHere },
            )).joinToString(" · ")
}
