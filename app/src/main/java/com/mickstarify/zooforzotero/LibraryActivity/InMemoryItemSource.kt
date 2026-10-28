package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.LoadedLibrary

/**
 * Answers queries from the open library held in memory: every item is loaded, and each
 * query walks them. [library] is read at each call (the open library can change), as are
 * [deviceState] and [searchFields].
 */
class InMemoryItemSource(
    private val library: () -> LoadedLibrary,
    private val deviceState: () -> DeviceState,
    private val searchFields: () -> Set<LibrarySearch.Field>,
) : LibraryItemSource {

    override fun query(query: LibraryQuery): List<Item> {
        val lib = library()
        val items = when (val place = query.place) {
            LibraryPlace.Library -> lib.getDisplayableItems()
            LibraryPlace.Unfiled -> lib.getItemsWithoutCollection()
            LibraryPlace.Trash -> lib.getTrashItems()
            LibraryPlace.MyPublications -> lib.getMyPublications()
            is LibraryPlace.Collection ->
                if (query.includeSubcollections) {
                    CollectionTree(lib.collections ?: emptyList())
                        .itemsIncludingSubcollections(place.key, lib::getItemsFromCollection)
                } else {
                    lib.getItemsFromCollection(place.key)
                }
        }
        val shown = if (query.place.isNarrowed) narrow(lib, items, query) else items
        return LibrarySort.sorted(shown, query.sort, query.ascending)
    }

    override fun tagCounts(): List<Pair<String, Int>> =
        LibraryItemFilter.tagCounts(library().getDisplayableItems())

    /** The query's search, then its filter. */
    private fun narrow(lib: LoadedLibrary, items: List<Item>, query: LibraryQuery): List<Item> {
        val filter = query.filter
        val displayed = LibraryItemFilter.applyDisplayFilters(
            items,
            onlyNotes = filter.hasNotes,
            onlyPdfs = filter.hasPdf,
            keysWithPdf = lib.keysWithPdf
        )
        val searched = if (query.search.isEmpty()) displayed else {
            val matches = searchIndex(lib)?.search(query.search)?.mapTo(HashSet()) { it.itemKey } ?: emptySet()
            displayed.filter { it.itemKey in matches }
        }
        if (!filter.isActive) return searched
        val state = if (filter.needsDeviceState) deviceState() else DeviceState()
        val onDeviceItems = if (filter.onDevice) owningItems(lib, state.onDevice) else emptySet()
        val editedItems = if (filter.editedHere) owningItems(lib, state.edited) + state.itemEdits else emptySet()
        return searched.filter { filter.matches(it, onDeviceItems, editedItems) }
    }

    /** For attachment keys: their parent items' keys, plus their own (standalone attachments). */
    private fun owningItems(lib: LoadedLibrary, attachmentKeys: Set<String>): Set<String> =
        attachmentKeys.flatMap { key ->
            listOfNotNull(key, lib.getItemWithKey(key)?.data?.get("parentItem"))
        }.toSet()

    @Volatile
    private var searchIndex: LibrarySearch.Index? = null

    /**
     * The library's search index for the chosen fields, built on first use after a load,
     * a sync or a change of fields (it keys on the item list's identity). Null before the
     * library loads.
     */
    private fun searchIndex(lib: LoadedLibrary): LibrarySearch.Index? {
        val items = lib.items ?: return null
        val fields = searchFields()
        // fields beyond title, creators and year arrive after startup; rebuilt when they have
        val allFields = lib.allFieldsLoaded || fields.none { it.needsAllFields }
        searchIndex?.takeIf { it.isFor(items, fields, allFields) }?.let { return it }
        return LibrarySearch.Index(items, fields, allFields).also { searchIndex = it }
    }

    /** Builds the search index in the background, ahead of a search. */
    fun warmSearch() {
        Thread { searchIndex(library()) }.start()
    }

    /** Items changed in place (a tag edit): the index holds their old text. */
    fun invalidateSearch() {
        searchIndex = null
    }
}
