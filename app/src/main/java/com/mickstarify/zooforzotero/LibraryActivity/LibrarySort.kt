package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.SortMethod
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import java.util.Locale

/** The list's order. Pure, so it can be tested without a database. */
object LibrarySort {

    /** The key an item sorts by under [method]; ties are broken by title, then by item key. */
    fun sortKey(item: Item, method: SortMethod): String = when (method) {
        SortMethod.TITLE -> item.getTitle().lowercase(Locale.ROOT)
        SortMethod.DATE -> item.getSortableDateString()
        SortMethod.DATE_ADDED -> item.getSortableDateAddedString()
        SortMethod.AUTHOR -> {
            val authorText = item.getAuthor().lowercase(Locale.ROOT)
            // force empty authors to the bottom. Just like the zotero desktop client.
            if (authorText == "") "zzz" else authorText
        }
    }

    /**
     * The fields [sortKey] and the title tie-break read under [method]; a note's title is
     * its text, read for notes only. With [readsCreators], the creators too.
     */
    fun fieldsRead(method: SortMethod): List<String> =
        listOf("itemType", "title", "caseName", "nameOfAct") + when (method) {
            SortMethod.DATE -> listOf("date")
            SortMethod.DATE_ADDED -> listOf("dateAdded")
            SortMethod.TITLE, SortMethod.AUTHOR -> emptyList()
        }

    fun readsCreators(method: SortMethod) = method == SortMethod.AUTHOR

    // Keys are computed once per item, not per comparison: a note's title parses its HTML.
    fun sorted(items: List<Item>, method: SortMethod, ascending: Boolean): List<Item> {
        val sorted = items
            .map { Triple(sortKey(it, method), it.getTitle().lowercase(Locale.ROOT), it) }
            // the key last, so that the order doesn't depend on the order items came in
            .sortedWith(compareBy<Triple<String, String, Item>>({ it.first }, { it.second }, { it.third.itemKey }))
            .map { it.third }
        return if (ascending) sorted else sorted.reversed()
    }
}
