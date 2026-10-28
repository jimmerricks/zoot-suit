package com.mickstarify.zooforzotero.ZoteroStorage.Database

import java.util.Locale

/**
 * What an item sorts by, one key per sort order (LibrarySort.sortKey reads these); stored
 * in ItemInfo so that the database can order a list. The title key is also every order's
 * tie-break.
 */
data class SortKeys(val title: String, val author: String, val date: String, val dateAdded: String) {
    companion object {
        fun title(item: Item): String = item.getTitle().lowercase(Locale.ROOT)

        // empty authors last, as in Zotero desktop
        fun author(item: Item): String = item.getAuthor().lowercase(Locale.ROOT).ifEmpty { "zzz" }

        fun date(item: Item): String = item.getSortableDateString()

        fun dateAdded(item: Item): String = item.getSortableDateAddedString()

        /** Needs the item's type, title fields (a note's text), date, date added and creators. */
        fun of(item: Item) = SortKeys(title(item), author(item), date(item), dateAdded(item))
    }
}
