package com.mickstarify.zooforzotero.ZoteroStorage.Database

import com.mickstarify.zooforzotero.LibraryActivity.LibrarySearch
import com.mickstarify.zooforzotero.LibraryActivity.LibrarySearch.Field

/**
 * An item's searchable text, one folded string per search field (LibrarySearch.searchText
 * of that field alone), stored in ItemInfo so that the database answers a search. Each of
 * the field's values ends with a separator, so a term found in an item's text for several
 * fields is found in the text of one of them: searching the columns is searching their
 * concatenation. Notes are searched through each child note's own [title] (its text).
 */
data class SearchText(
    val title: String,
    val creators: String,
    val year: String,
    val publication: String,
    val abstract: String,
    val tags: String,
    val other: String,
) {
    companion object {
        /** The fields stored here; NOTES is answered from the children's [title]. */
        val STORED = listOf(Field.TITLE, Field.CREATORS, Field.YEAR, Field.PUBLICATION, Field.ABSTRACT, Field.TAGS, Field.OTHER)

        /** ItemInfo's column for a stored field. */
        fun column(field: Field): String = when (field) {
            Field.TITLE -> "searchTitle"
            Field.CREATORS -> "searchCreators"
            Field.YEAR -> "searchYear"
            Field.PUBLICATION -> "searchPublication"
            Field.ABSTRACT -> "searchAbstract"
            Field.TAGS -> "searchTags"
            Field.OTHER -> "searchOther"
            Field.NOTES -> throw IllegalArgumentException("notes are searched through the child notes")
        }

        /** Needs all of the item's fields, its creators and its tags. */
        fun of(item: Item): SearchText {
            fun text(field: Field) = LibrarySearch.searchText(item, setOf(field))
            return SearchText(
                text(Field.TITLE), text(Field.CREATORS), text(Field.YEAR), text(Field.PUBLICATION),
                text(Field.ABSTRACT), text(Field.TAGS), text(Field.OTHER),
            )
        }
    }
}
