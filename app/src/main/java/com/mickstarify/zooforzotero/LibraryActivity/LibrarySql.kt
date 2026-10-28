package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.SearchText

/**
 * The SQL behind a [LibraryQuery]: the keys of the items in its place, narrowed by the
 * filter's tags, "has PDF" and "has notes", and by the search once the library's items
 * have their SearchText. The device-state filters (and a search before then) are applied
 * to the keys afterwards (RoomItemSource). Each condition says in SQL what the
 * in-memory list (InMemoryItemSource over ZoteroDB) decides in Kotlin; ItemSourceEquivalenceTest
 * holds the two to the same answers.
 */
object LibrarySql {

    private const val PDF = "'application/pdf'"

    // a child note n that parses (Item.adoptChild: Note needs its key and text)
    private const val NOTE = "n.deleted = 0 AND n.itemType = 'note' " +
            "AND EXISTS (SELECT 1 FROM ItemData nd WHERE nd.parent = n.itemKey AND nd.name = 'note') " +
            "AND EXISTS (SELECT 1 FROM ItemData nk WHERE nk.parent = n.itemKey AND nk.name = 'key')"

    /**
     * LibrarySearch.TextIndex in SQL: a top-level item (the index holds no others) whose
     * text for [fields] contains every term; no terms, no items. A term is looked for in
     * each field's column (SearchText) and, for NOTES, in the text of each child note.
     */
    private fun search(query: String, fields: Set<LibrarySearch.Field>, where: MutableList<String>, args: MutableList<Any>) {
        val terms = LibrarySearch.terms(query)
        if (terms.isEmpty()) {
            where += "0"
            return
        }
        where += "i.parent IS NULL"
        val columns = SearchText.STORED.filter { it in fields }.map { SearchText.column(it) }
        val notes = LibrarySearch.Field.NOTES in fields
        for (term in terms) {
            val anyField = columns.map { "instr(i.$it, ?) > 0" }.toMutableList()
            repeat(columns.size) { args.add(term) }
            if (notes) {
                anyField += "EXISTS (SELECT 1 FROM ItemInfo n WHERE n.parent = i.itemKey AND $NOTE " +
                        "AND instr(n.searchTitle, ?) > 0)"
                args.add(term)
            }
            where += if (anyField.isEmpty()) "0" else anyField.joinToString(" OR ", "(", ")")
        }
    }

    /**
     * @param collectionKeys for a collection place: the collection and, if asked, its
     *   subcollections (an item in several counts once)
     * @param ordered in the query's sort order, from the stored SortKeys (all of the
     *   library's items must have them), as LibrarySort.sorted orders: the method's key,
     *   then the title's, then the item key, all reversed for descending. SQLite compares
     *   text by code point, Kotlin by UTF-16 unit; they differ only between a character
     *   beyond U+FFFF (emoji) and one from U+E000-U+FFFF.
     * @param searchFields the fields to answer the query's search from, in SQL (all of the
     *   library's items must have their SearchText); null: the search is left out
     */
    fun keys(
        query: LibraryQuery, groupId: Int, collectionKeys: List<String>, ordered: Boolean = false,
        searchFields: Set<LibrarySearch.Field>? = null,
    ): Pair<String, List<Any>> {
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
            if (filter.hasNotes) where += "EXISTS (SELECT 1 FROM ItemInfo n WHERE n.parent = i.itemKey AND $NOTE)"
            if (searchFields != null && query.search.isNotEmpty()) search(query.search, searchFields, where, args)
        }
        val order = if (!ordered) "" else {
            val direction = if (query.ascending) "" else " DESC"
            " ORDER BY i.${LibrarySort.column(query.sort)}$direction, i.sortTitle$direction, i.itemKey$direction"
        }
        return "SELECT i.itemKey FROM ItemInfo i WHERE " + where.joinToString(" AND ") + order to args
    }
}
