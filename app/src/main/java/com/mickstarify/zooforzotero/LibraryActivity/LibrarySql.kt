package com.mickstarify.zooforzotero.LibraryActivity

/**
 * The SQL behind a [LibraryQuery]: the keys of the items in its place, narrowed by the
 * filter's tags, "has PDF" and "has notes". The search and the device-state filters are
 * applied to the keys afterwards (RoomItemSource). Each condition says in SQL what the
 * in-memory list (InMemoryItemSource over ZoteroDB) decides in Kotlin; ItemSourceEquivalenceTest
 * holds the two to the same answers.
 */
object LibrarySql {

    private const val PDF = "'application/pdf'"

    /**
     * @param collectionKeys for a collection place: the collection and, if asked, its
     *   subcollections (an item in several counts once)
     * @param ordered in the query's sort order, from the stored SortKeys (all of the
     *   library's items must have them), as LibrarySort.sorted orders: the method's key,
     *   then the title's, then the item key, all reversed for descending. SQLite compares
     *   text by code point, Kotlin by UTF-16 unit; they differ only between a character
     *   beyond U+FFFF (emoji) and one from U+E000-U+FFFF.
     */
    fun keys(query: LibraryQuery, groupId: Int, collectionKeys: List<String>, ordered: Boolean = false): Pair<String, List<Any>> {
        val where = mutableListOf("i.`group` = ?")
        val args = mutableListOf<Any>(groupId)
        when (query.place) {
            LibraryPlace.Library -> where += "i.deleted = 0 AND i.parent IS NULL"
            LibraryPlace.Unfiled -> where += "i.deleted = 0 AND i.parent IS NULL AND " +
                    "NOT EXISTS (SELECT 1 FROM ItemCollection c WHERE c.itemKey = i.itemKey)"
            LibraryPlace.Trash -> where += "i.deleted = 1"
            LibraryPlace.MyPublications -> where += "i.deleted = 0 AND EXISTS (SELECT 1 FROM ItemData d " +
                    "WHERE d.parent = i.itemKey AND d.name = 'inPublications' AND d.value = 'true')"
            is LibraryPlace.Collection -> {
                where += "i.deleted = 0 AND EXISTS (SELECT 1 FROM ItemCollection c WHERE c.itemKey = i.itemKey " +
                        "AND c.collectionKey IN (${collectionKeys.joinToString(",") { "?" }}))"
                args.addAll(collectionKeys)
            }
        }
        if (query.place.isNarrowed) {
            val filter = query.filter
            for (tag in filter.tags) {
                where += "EXISTS (SELECT 1 FROM ItemTags t WHERE t.parent = i.itemKey AND t.tag = ?)"
                args.add(tag)
            }
            // a PDF attachment that opens (Item.pdfOwnerKey): a child's counts for its parent,
            // a standalone one for itself
            if (filter.hasPdf) where += "(EXISTS (SELECT 1 FROM ItemInfo a WHERE a.parent = i.itemKey " +
                    "AND a.deleted = 0 AND a.itemType = 'attachment' AND a.contentType = $PDF) " +
                    "OR (i.parent IS NULL AND i.itemType = 'attachment' AND i.contentType = $PDF))"
            // a child note that parses (Item.adoptChild: Note needs its key and text)
            if (filter.hasNotes) where += "EXISTS (SELECT 1 FROM ItemInfo n WHERE n.parent = i.itemKey " +
                    "AND n.deleted = 0 AND n.itemType = 'note' " +
                    "AND EXISTS (SELECT 1 FROM ItemData nd WHERE nd.parent = n.itemKey AND nd.name = 'note') " +
                    "AND EXISTS (SELECT 1 FROM ItemData nk WHERE nk.parent = n.itemKey AND nk.name = 'key'))"
        }
        val order = if (!ordered) "" else {
            val direction = if (query.ascending) "" else " DESC"
            " ORDER BY i.${LibrarySort.column(query.sort)}$direction, i.sortTitle$direction, i.itemKey$direction"
        }
        return "SELECT i.itemKey FROM ItemInfo i WHERE " + where.joinToString(" AND ") + order to args
    }
}
