package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import java.util.concurrent.atomic.AtomicInteger

/**
 * Answers queries from the database: the place, tags, "has PDF" and "has notes" in SQL
 * ([LibrarySql]), the search and the device-state filters on the keys that come back, and
 * then only the matching items are loaded (with their attachments and notes) and sorted.
 * Nothing of the library is held between queries but the search's text index.
 * [groupId] is the open library, read at each call, like [collections], [deviceState]
 * and [searchFields].
 */
class RoomItemSource(
    private val database: ZoteroDatabase,
    private val groupId: () -> Int,
    private val collections: () -> List<Collection>,
    private val deviceState: () -> DeviceState,
    private val searchFields: () -> Set<LibrarySearch.Field>,
) : LibraryItemSource {

    override fun query(query: LibraryQuery): LibraryRows {
        val group = groupId()
        val place = query.place
        val collectionKeys = when {
            place !is LibraryPlace.Collection -> emptyList()
            query.includeSubcollections -> CollectionTree(collections()).withDescendants(place.key)
            else -> listOf(place.key)
        }
        // in SQL by the stored sort keys; items from before they existed are sorted here
        // until the library's are filled in (ZoteroDatabase.fillMissingSortKeys)
        val ordered = hasSortKeys(group)
        val (sql, args) = LibrarySql.keys(query, group, collectionKeys, ordered)
        var keys = database.queryKeys(sql, args)
        if (place.isNarrowed) keys = narrow(group, keys, query)
        if (!ordered) {
            val sortItems = database.loadSortItems(
                keys, LibrarySort.fieldsRead(query.sort), LibrarySort.readsCreators(query.sort)
            )
            keys = LibrarySort.sorted(sortItems, query.sort, query.ascending).map { it.itemKey }
        }
        // whole rows load a page at a time
        return DatabaseRows(database, keys)
    }

    // libraries whose items all have SortKeys; writeItem stores them with every item, so
    // one stays complete, but it's checked again after invalidate() all the same
    private val sortKeysComplete = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** Whether all of library [group]'s items have SortKeys: a scan of ItemInfo, so it's remembered once true. */
    private fun hasSortKeys(group: Int): Boolean {
        if (group in sortKeysComplete) return true
        return (!database.lacksSortKeys(group)).also { if (it) sortKeysComplete += group }
    }

    private class DatabaseRows(private val database: ZoteroDatabase, override val keys: List<String>) : LibraryRows {
        override fun load(from: Int, to: Int): List<Item?> {
            val range = keys.subList(from, to)
            val byKey = database.loadItemsByKeys(range).associateBy { it.itemKey }
            return range.map { byKey[it] }
        }
    }

    /** The query's search and device-state filters, which SQL doesn't answer. */
    private fun narrow(group: Int, keys: List<String>, query: LibraryQuery): List<String> {
        var result = keys
        if (query.search.isNotEmpty()) {
            val matches = searchIndex(group).search(query.search)
            result = result.filter { it in matches }
        }
        val filter = query.filter
        if (filter.needsDeviceState) {
            val state = deviceState()
            if (filter.onDevice) {
                val owners = owningItems(state.onDevice)
                result = result.filter { it in owners }
            }
            if (filter.editedHere) {
                val owners = owningItems(state.edited) + state.itemEdits
                result = result.filter { it in owners }
            }
        }
        return result
    }

    /** For attachment keys: their parent items' keys, plus their own (standalone attachments). */
    private fun owningItems(attachmentKeys: Set<String>): Set<String> {
        if (attachmentKeys.isEmpty()) return emptySet()
        val parents = database.parentsOf(attachmentKeys)
        return attachmentKeys + parents.values.filterNotNull()
    }

    override fun tagCounts(): List<Pair<String, Int>> = database.tagCounts(groupId())

    private class CachedIndex(
        val group: Int,
        val fields: Set<LibrarySearch.Field>,
        val generation: Int,
        val index: LibrarySearch.TextIndex,
    )

    @Volatile
    private var cached: CachedIndex? = null

    // counts invalidate(); an index built before the latest one is rebuilt
    private val generation = AtomicInteger()

    /**
     * The open library's searchable text for the chosen fields, by item key; built from
     * its top-level items on first use after a change of library, fields or data.
     */
    private fun searchIndex(group: Int): LibrarySearch.TextIndex {
        val fields = searchFields()
        val gen = generation.get()
        cached?.takeIf { it.group == group && it.fields == fields && it.generation == gen }?.let { return it.index }
        val items = database.loadItemsByKeys(
            database.topLevelKeys(group),
            listFieldsOnly = fields.none { it.needsAllFields },
            withChildren = LibrarySearch.Field.NOTES in fields,
        )
        return LibrarySearch.TextIndex(items, fields).also { cached = CachedIndex(group, fields, gen, it) }
    }

    override fun warmSearch() {
        searchIndex(groupId())
    }

    override fun invalidate() {
        generation.incrementAndGet()
        sortKeysComplete.clear()
    }
}
