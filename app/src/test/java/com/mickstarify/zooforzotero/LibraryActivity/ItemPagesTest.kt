package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItemPagesTest {

    private fun item(key: String) = Item().apply {
        itemInfo = ItemInfo(key, version = 1, deleted = false)
        itemData = emptyList()
        creators = emptyList()
        tags = emptyList()
        collections = emptyList()
    }

    private val rows = LoadedRows((0 until 95).map { item("K%03d".format(it)) })

    // loads are held until run, as the list thread would
    private val reads = mutableListOf<Pair<IntRange, (List<Item?>?) -> Unit>>()
    private fun pages(first: List<Item?> = rows.load(0, 10)) =
        ItemPages(rows, first, { r, from, to, done -> reads += (from until to) to { done(it) } }, pageSize = 10, keepPages = 3)

    private fun runReads(fail: Boolean = false) {
        val pending = reads.toList()
        reads.clear()
        for ((range, done) in pending) done(if (fail) null else rows.load(range.first, range.last + 1))
    }

    @Test
    fun `the first page is there at once`() {
        val pages = pages()
        assertEquals("K003", pages.get(3)?.itemKey)
        assertEquals(95, pages.size)
    }

    @Test
    fun `a row on an unread page is asked for, then there`() {
        val pages = pages()
        val changed = mutableListOf<Pair<Int, Int>>()
        pages.onPageLoaded = { from, count -> changed += from to count }
        assertNull(pages.get(42))
        assertEquals(listOf(40 until 50), reads.map { it.first })
        runReads()
        assertEquals("K042", pages.get(42)?.itemKey)
        assertEquals(listOf(40 to 10), changed)
    }

    @Test
    fun `near a page's end the next is read ahead, and the last page is short`() {
        val pages = pages()
        pages.get(85)
        assertEquals(listOf(80 until 90), reads.map { it.first })
        runReads()
        pages.get(88)
        assertEquals(listOf(90 until 95), reads.map { it.first })
        runReads()
        assertEquals("K094", pages.get(94)?.itemKey)
        assertNull(pages.get(95))
    }

    @Test
    fun `a page is asked for once while it loads`() {
        val pages = pages()
        pages.get(55)
        pages.get(56)
        assertEquals(1, reads.size)
    }

    @Test
    fun `only the pages used last are kept`() {
        val pages = pages()
        for (p in listOf(25, 45, 65)) {
            pages.get(p)
            runReads()
        }
        // kept: 2, 4, 6 (3 pages); page 0 went first
        reads.clear()
        assertNull(pages.get(5))
        assertEquals(listOf(0 until 10), reads.map { it.first })
    }

    @Test
    fun `a failed read is asked for again`() {
        val pages = pages()
        pages.get(33)
        runReads(fail = true)
        assertNull(pages.get(33))
        assertEquals(listOf(30 until 40), reads.map { it.first })
    }
}
