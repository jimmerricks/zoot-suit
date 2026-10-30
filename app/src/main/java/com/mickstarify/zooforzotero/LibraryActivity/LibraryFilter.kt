package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item

/**
 * The library list's filter. Every condition that is set must hold (AND): an item must
 * carry all of [tags]; with [hasPdf] / [hasHtml] / [hasOtherAttachment] / [hasNotes], have
 * a PDF / an HTML snapshot / another attachment (not a web link) / notes (applied by
 * LibraryItemFilter.applyDisplayFilters); with [pdfOnDevice] / [htmlOnDevice] / [editedHere],
 * have a PDF / an HTML snapshot on this device / an attachment with edits waiting to sync.
 * An empty filter lets everything through.
 */
data class LibraryFilter(
    val tags: Set<String> = emptySet(),
    val pdfOnDevice: Boolean = false,
    val editedHere: Boolean = false,
    val hasPdf: Boolean = false,
    val hasNotes: Boolean = false,
    val hasHtml: Boolean = false,
    val hasOtherAttachment: Boolean = false,
    val htmlOnDevice: Boolean = false,
) {
    val isActive: Boolean
        get() = tags.isNotEmpty() || pdfOnDevice || htmlOnDevice || editedHere || hasPdf || hasNotes ||
                hasHtml || hasOtherAttachment

    val needsDeviceState: Boolean get() = pdfOnDevice || htmlOnDevice || editedHere

    /**
     * @param pdfItems keys of items with a PDF on this device (the attachment's parent, or
     *   the attachment itself if it stands alone)
     * @param htmlItems the same for HTML snapshots
     * @param editedItems the same for attachments with edits waiting to sync
     */
    fun matches(item: Item, pdfItems: Set<String>, htmlItems: Set<String>, editedItems: Set<String>): Boolean {
        if (tags.isNotEmpty() && !item.tags.map { it.tag }.toSet().containsAll(tags)) return false
        if (pdfOnDevice && item.itemKey !in pdfItems) return false
        if (htmlOnDevice && item.itemKey !in htmlItems) return false
        if (editedHere && item.itemKey !in editedItems) return false
        return true
    }

    /** Short description for the toolbar, e.g. "to-read, method · on this device". */
    fun describe(): String = (tags.sortedWith(String.CASE_INSENSITIVE_ORDER) +
            listOfNotNull(
                "has PDF".takeIf { hasPdf },
                "has HTML".takeIf { hasHtml },
                "has other attachment".takeIf { hasOtherAttachment },
                "has notes".takeIf { hasNotes },
                "PDF on this device".takeIf { pdfOnDevice },
                "HTML on this device".takeIf { htmlOnDevice },
                "edits to sync".takeIf { editedHere },
            )).joinToString(" · ")
}
