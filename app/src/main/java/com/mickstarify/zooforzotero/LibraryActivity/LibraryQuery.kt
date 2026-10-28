package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.SortMethod
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item

/** Where in the open library the list is. */
sealed interface LibraryPlace {
    /** Every top-level item ("My Library", or a group's top). */
    data object Library : LibraryPlace
    data object Unfiled : LibraryPlace
    data object Trash : LibraryPlace
    data object MyPublications : LibraryPlace
    data class Collection(val key: String) : LibraryPlace

    /** Trash and My Publications are shown whole: the search and the filter don't apply. */
    val isNarrowed: Boolean get() = this != Trash && this != MyPublications
}

/**
 * What the library list shows: a place in the open library, narrowed by the search and
 * the filter, in an order. The list is built from this alone, by a [LibraryItemSource],
 * so how items are found (in memory now; SQL, a page at a time, later) can change
 * without the screen knowing.
 */
data class LibraryQuery(
    val place: LibraryPlace,
    val search: String = "",
    val filter: LibraryFilter = LibraryFilter(),
    val sort: SortMethod = SortMethod.TITLE,
    val ascending: Boolean = true,
    /** With a collection: its subcollections' items too, each once. */
    val includeSubcollections: Boolean = false,
)

/** Attachment keys whose file is on this device / has edits to sync, and keys of items with metadata edits to sync. */
data class DeviceState(
    val onDevice: Set<String> = emptySet(),
    val edited: Set<String> = emptySet(),
    val itemEdits: Set<String> = emptySet(),
)

/** Answers [LibraryQuery]s for the open library. Blocking. */
interface LibraryItemSource {
    /** The query's items, sorted. */
    fun query(query: LibraryQuery): List<Item>

    /** Every tag on the library's top-level items, with the number of items carrying it; by name. */
    fun tagCounts(): List<Pair<String, Int>>

    /** Prepares what a search needs, ahead of one. */
    fun warmSearch()

    /** The library changed (a sync, an edit on this device): forget what was derived from it. */
    fun invalidate()
}
