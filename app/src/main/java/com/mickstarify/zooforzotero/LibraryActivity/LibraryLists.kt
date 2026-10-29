package com.mickstarify.zooforzotero.LibraryActivity

import android.util.Log
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.Executors

private const val TAG = "LibraryLists"

/**
 * The library screen's lists: each place in the open library as a database query after
 * the library's search, filter and sort, read a page at a time off the main thread; and
 * the open library's tags with their counts.
 *
 * @param deviceState what's downloaded and edited, for the filters that depend on it
 */
class LibraryLists(
    private val ui: LibraryUi,
    private val library: OpenLibrary,
    private val zoteroDatabase: ZoteroDatabase,
    private val preferences: PreferenceManager,
    private val deviceState: () -> DeviceState,
) {
    private val state get() = library.state
    private val zoteroDB get() = library.db

    /** The list's queries must be run again: the library changed (a sync, an edit). */
    fun invalidate() = itemSource.invalidate()

    /**
     * Answers the list's queries from the database, for the open library (read at each
     * one).
     */
    private val itemSource: LibraryItemSource by lazy {
        RoomItemSource(
            database = zoteroDatabase,
            groupId = { zoteroDB.groupID },
            collections = { library.collections },
            deviceState = deviceState,
            searchFields = { preferences.getSearchFields() },
        )
    }

    // one thread: lists are built in the order asked for, never two at once
    private val listScheduler = Schedulers.from(Executors.newSingleThreadExecutor())

    // the latest list asked for; an older one that finishes later is dropped
    private var listGeneration = 0

    /**
     * Builds the list for [place] in the open library, after [librarySearch] and
     * [libraryFilter] (Trash and My Publications are shown whole), in the library's sort
     * order, off the main thread, with its first page of rows; then sets
     * [lastFilteredCount] and calls [onResult] on the main thread, unless another list was
     * asked for meanwhile. Later pages are read as the list scrolls ([ItemPages]). Call on
     * the main thread.
     */
    fun listItems(place: LibraryPlace, onResult: (ItemPages) -> Unit) {
        val groupId = state.currentGroup.id
        val query = LibraryQuery(
            place = place,
            search = librarySearch,
            filter = libraryFilter,
            sort = preferences.getSortMethod(groupId),
            ascending = preferences.isSortedAscendingly(groupId),
            includeSubcollections = preferences.showSubcollectionItems(),
        )
        val generation = ++listGeneration
        val start = System.nanoTime()
        Single.fromCallable {
            val rows = itemSource.query(query)
            rows to rows.load(0, minOf(rows.size, ItemPages.PAGE_SIZE))
        }
            .subscribeOn(listScheduler)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (rows, firstPage) ->
                Log.d(TAG, "listItems($place) - ${rows.size} items in ${(System.nanoTime() - start) / 1_000_000} ms")
                if (generation != listGeneration) return@subscribe
                lastFilteredCount = if (place.isNarrowed) rows.size else null
                onResult(ItemPages(rows, firstPage, ::loadRows))
            }, { e ->
                Log.e(TAG, "building the list for $place failed: $e")
                if (generation == listGeneration) {
                    ui.createErrorAlert("Couldn't show the list", "Details: ${e.message ?: e}") {}
                }
            })
    }

    /**
     * The open library's list filter. Each library has its own. With "Keep the filter"
     * on, it is saved and stays when another collection opens; off (the default), it
     * clears then, and the app starts without one. See [clearOnCollectionChange].
     */
    var libraryFilter: LibraryFilter
        get() {
            clearOnCollectionChange()
            return savedFilters.getOrPut(state.currentGroup.id) {
                if (preferences.keepFilter()) preferences.getLibraryFilter(state.currentGroup.id) else LibraryFilter()
            }
        }
        set(value) {
            val id = state.currentGroup.id
            savedFilters[id] = value
            preferences.setLibraryFilter(id, value)
        }
    private val savedFilters = mutableMapOf<Int, LibraryFilter>() // by library (group id)

    /**
     * The open library's search, narrowing every list like [libraryFilter]; "" for none.
     * Each library has its own, kept or cleared like the filter ("Keep the search").
     */
    var librarySearch: String
        get() {
            clearOnCollectionChange()
            return searches.getOrPut(state.currentGroup.id) {
                if (preferences.keepSearch()) preferences.getLibrarySearch(state.currentGroup.id) else ""
            }
        }
        set(value) {
            val id = state.currentGroup.id
            searches[id] = value.trim()
            preferences.setLibrarySearch(id, value.trim())
        }
    private val searches = mutableMapOf<Int, String>() // by library (group id)

    /** The library and collection the search and filter were last read for. */
    private var shownCollection: Pair<Int, String>? = null

    /**
     * Clears the search and the filter, unless set to be kept, when the collection (or
     * the library) differs from the last one they were read for. Back
     * within the same collection keep them. Called by their getters, which every list,
     * dialog and subtitle reads after navigation has settled.
     */
    private fun clearOnCollectionChange() {
        val now = state.currentGroup.id to state.currentCollection
        val before = shownCollection
        shownCollection = now
        if (before == null || before == now) return
        val id = now.first
        if (!preferences.keepSearch()) {
            searches[id] = ""
            preferences.setLibrarySearch(id, "")
        }
        if (!preferences.keepFilter()) {
            savedFilters[id] = LibraryFilter()
            preferences.setLibraryFilter(id, LibraryFilter())
        }
    }

    /**
     * How many items the list shows, after search and filter; null if the current list
     * doesn't go through the filter (trash, My Publications). For the toolbar's count.
     */
    var lastFilteredCount: Int? = null
        private set

    /** Reads rows of a list on the list's thread, for [ItemPages]; null to it if that fails. */
    private fun loadRows(rows: LibraryRows, from: Int, to: Int, onLoaded: (List<Item?>?) -> Unit) {
        Single.fromCallable { rows.load(from, to) }
            .subscribeOn(listScheduler)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ onLoaded(it) }, { e ->
                Log.e(TAG, "reading rows $from-$to failed: $e")
                onLoaded(null)
            })
    }

    /** Prepares the search in the background (on the list's thread) while the search dialog is open. */
    fun warmSearchIndex() {
        if (!library.isLoaded()) return
        Completable.fromAction { itemSource.warmSearch() }
            .subscribeOn(listScheduler)
            .subscribe({}, { e -> Log.e(TAG, "preparing the search failed: $e") })
    }

    // a GROUP BY over every tag: 40 ms on the emulator, too long for the main thread on a tablet
    fun loadTags(onResult: (List<Pair<String, Int>>) -> Unit) {
        Single.fromCallable { itemSource.tagCounts() }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(onResult) { e ->
                Log.e(TAG, "counting tags failed: $e")
                onResult(emptyList())
            }
    }
}
