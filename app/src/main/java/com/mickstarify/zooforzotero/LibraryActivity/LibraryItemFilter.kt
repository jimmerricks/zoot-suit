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

    /** Items matching a free-text search; see [Item.query]. Null means no library loaded. */
    fun matchingQuery(items: List<Item>?, query: String): List<Item> {
        return items?.filter { it.query(query) } ?: emptyList()
    }

    /** Collections whose name contains [query], case-insensitively. */
    fun collectionsMatching(collections: List<ZoteroCollection>?, query: String): List<ZoteroCollection> {
        val queryUpper = query.uppercase()
        return collections?.filter { it.name.uppercase().contains(queryUpper) } ?: emptyList()
    }
}
