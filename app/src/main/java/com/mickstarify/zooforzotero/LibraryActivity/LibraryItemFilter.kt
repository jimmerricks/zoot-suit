package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection as ZoteroCollection

/**
 * The library screen's item and collection filtering.
 *
 * Previously inlined in LibraryActivityModel. Everything here is pure: the
 * caller supplies the preference flags and an attachment lookup, so the
 * rules can be unit tested without a database or Android.
 */
object LibraryItemFilter {

    /**
     * Applies the "only notes" / "only PDFs" display preferences. With both
     * set, an item must satisfy both. [attachmentsFor] maps an item key to
     * its child attachments.
     */
    fun applyDisplayFilters(
        items: List<Item>,
        onlyNotes: Boolean,
        onlyPdfs: Boolean,
        attachmentsFor: (String) -> List<Item>
    ): List<Item> {
        var newItems = items
        if (onlyNotes) {
            newItems = newItems.filter { it.notes.size > 0 }
        }
        if (onlyPdfs) {
            newItems = newItems.filter { item ->
                attachmentsFor(item.itemKey).any { it.data["contentType"] == "application/pdf" }
            }
        }
        return newItems
    }

    /**
     * Every tag in [items] with the number of items carrying it, sorted by name
     * case-insensitively. An item tagged twice with the same tag counts once.
     */
    fun tagCounts(items: List<Item>?): List<Pair<String, Int>> {
        if (items == null) return emptyList()
        val counts = HashMap<String, Int>()
        for (item in items) {
            for (tag in item.tags.map { it.tag }.toSet()) {
                counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        return counts.toList().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
    }

    /** Collections whose name contains every term of [query]; see [LibrarySearch.terms]. */
    fun collectionsMatching(collections: List<ZoteroCollection>?, query: String): List<ZoteroCollection> {
        val terms = LibrarySearch.terms(query)
        if (terms.isEmpty()) return emptyList()
        return collections?.filter { c -> LibrarySearch.fold(c.name).let { n -> terms.all { n.contains(it) } } } ?: emptyList()
    }
}
