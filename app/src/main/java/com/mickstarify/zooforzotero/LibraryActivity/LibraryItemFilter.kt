package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item

/**
 * The library screen's item filtering.
 *
 * Previously inlined in LibraryActivityModel. Everything here is pure: the
 * caller supplies the preference flags and an attachment lookup, so the
 * rules can be unit tested without a database or Android.
 */
object LibraryItemFilter {

    /**
     * Applies the filter's "has notes", "has PDF", "has HTML snapshot" and "has other
     * attachment"; an item must satisfy each that is set. [keysWithPdf], [keysWithHtml],
     * [keysWithOther]: keys of the items with one (ZoteroDB.keysWithPdf and the rest).
     */
    fun applyDisplayFilters(
        items: List<Item>,
        onlyNotes: Boolean,
        onlyPdfs: Boolean,
        keysWithPdf: Set<String>,
        onlyHtml: Boolean = false,
        keysWithHtml: Set<String> = emptySet(),
        onlyOther: Boolean = false,
        keysWithOther: Set<String> = emptySet(),
    ): List<Item> {
        var newItems = items
        if (onlyNotes) {
            newItems = newItems.filter { it.notes.size > 0 }
        }
        if (onlyPdfs) {
            newItems = newItems.filter { it.itemKey in keysWithPdf }
        }
        if (onlyHtml) {
            newItems = newItems.filter { it.itemKey in keysWithHtml }
        }
        if (onlyOther) {
            newItems = newItems.filter { it.itemKey in keysWithOther }
        }
        return newItems
    }

    /**
     * Every tag in [items] with the number of items carrying it, sorted by name
     * case-insensitively (then exactly, so that "Method" and "method" keep one order). An item tagged twice with the same tag counts once.
     */
    fun tagCounts(items: List<Item>?): List<Pair<String, Int>> {
        if (items == null) return emptyList()
        val counts = HashMap<String, Int>()
        for (item in items) {
            for (tag in item.tags.map { it.tag }.toSet()) {
                counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        return counts.toList()
            .sortedWith(compareBy<Pair<String, Int>, String>(String.CASE_INSENSITIVE_ORDER) { it.first }.thenBy { it.first })
    }
}
