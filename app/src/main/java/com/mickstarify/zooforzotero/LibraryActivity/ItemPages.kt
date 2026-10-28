package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item

/**
 * The list on screen: [rows]' items in order, read a page at a time as they come into
 * view. [get] asks for the page around a position (and its neighbour near a page's edge),
 * and only the [keepPages] pages used last are kept. [firstPage] is read with the query,
 * so the list never opens on placeholders.
 *
 * Main thread only. [loader] reads rows off it and calls back on it, with null if the
 * read failed (the page is asked for again when next in view).
 */
class ItemPages(
    val rows: LibraryRows,
    firstPage: List<Item?>,
    private val loader: (rows: LibraryRows, from: Int, to: Int, onLoaded: (List<Item?>?) -> Unit) -> Unit,
    private val pageSize: Int = PAGE_SIZE,
    private val keepPages: Int = KEEP_PAGES,
) {
    val size: Int get() = rows.size

    /** Called when a page's rows arrive: positions [from] until from + count changed. */
    var onPageLoaded: ((from: Int, count: Int) -> Unit)? = null

    // page index -> rows; in access order, so the first is the one used longest ago
    private val pages = LinkedHashMap<Int, List<Item?>>(16, 0.75f, true)
    private val loading = HashSet<Int>()

    init {
        if (firstPage.isNotEmpty()) pages[0] = firstPage
    }

    /** The item at [position] if its page is read, else null (and the page is asked for). */
    fun get(position: Int): Item? {
        val page = position / pageSize
        val offset = position % pageSize
        request(page)
        // read ahead in the direction a scroll is likely to go
        if (offset >= pageSize * 3 / 4) request(page + 1)
        if (offset < pageSize / 4) request(page - 1)
        return pages[page]?.getOrNull(offset)
    }

    private fun request(page: Int) {
        if (page < 0 || page * pageSize >= size || pages.containsKey(page) || page in loading) return
        loading += page
        val from = page * pageSize
        loader(rows, from, minOf(size, from + pageSize)) { items ->
            loading -= page
            if (items == null) return@loader
            pages[page] = items
            while (pages.size > keepPages) pages.remove(pages.keys.first())
            onPageLoaded?.invoke(from, items.size)
        }
    }

    companion object {
        const val PAGE_SIZE = 100
        const val KEEP_PAGES = 8
    }
}
