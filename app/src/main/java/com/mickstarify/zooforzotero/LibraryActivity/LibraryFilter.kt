package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item

/**
 * The library list's filter. Every condition that is set must hold (AND): an item must
 * carry all of [tags]; with [hasPdf] / [hasNotes], have a PDF attachment / notes (applied
 * by LibraryItemFilter.applyDisplayFilters); with [onDevice] / [editedHere], have an
 * attachment whose PDF is on this device / has edits waiting to sync. An empty filter
 * lets everything through.
 */
data class LibraryFilter(
    val tags: Set<String> = emptySet(),
    val onDevice: Boolean = false,
    val editedHere: Boolean = false,
    val hasPdf: Boolean = false,
    val hasNotes: Boolean = false,
) {
    val isActive: Boolean get() = tags.isNotEmpty() || onDevice || editedHere || hasPdf || hasNotes

    val needsDeviceState: Boolean get() = onDevice || editedHere

    /**
     * @param onDeviceItems keys of items with an attachment whose file is on this device
     *   (the attachment's parent, or the attachment itself if it stands alone)
     * @param editedItems the same for attachments with edits waiting to sync
     */
    fun matches(item: Item, onDeviceItems: Set<String>, editedItems: Set<String>): Boolean {
        if (tags.isNotEmpty() && !item.tags.map { it.tag }.toSet().containsAll(tags)) return false
        if (onDevice && item.itemKey !in onDeviceItems) return false
        if (editedHere && item.itemKey !in editedItems) return false
        return true
    }

    /** Short description for the toolbar, e.g. "to-read, method · on this device". */
    fun describe(): String = (tags.sortedWith(String.CASE_INSENSITIVE_ORDER) +
            listOfNotNull(
                "has PDF".takeIf { hasPdf },
                "has notes".takeIf { hasNotes },
                "on this device".takeIf { onDevice },
                "edits to sync".takeIf { editedHere },
            )).joinToString(" · ")
}
