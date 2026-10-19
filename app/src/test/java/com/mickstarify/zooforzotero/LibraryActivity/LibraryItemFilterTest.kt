package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Creator
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemData
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection as ZoteroCollection

class LibraryItemFilterTest {

    private fun item(
        key: String,
        data: Map<String, String> = emptyMap(),
        creators: List<Pair<String, String>> = emptyList(),
        tags: List<String> = emptyList(),
        withNote: Boolean = false
    ) = Item().apply {
        itemInfo = ItemInfo(key, version = 1, deleted = false)
        itemData = data.map { (name, value) -> ItemData(key, name, value, "String") }
        this.creators = creators.mapIndexed { i, (first, last) -> Creator(key, first, last, "author", i) }
        this.tags = tags.map { ItemTag(key, it) }
        collections = emptyList()
        if (withNote) notes.add(Note())
    }

    private fun pdf(parent: String) =
        item("$parent-PDF", mapOf("itemType" to "attachment", "contentType" to "application/pdf"))

    private fun html(parent: String) =
        item("$parent-HTML", mapOf("itemType" to "attachment", "contentType" to "text/html"))

    private val withNote = item("NOTE", withNote = true)
    private val withPdf = item("PDF")
    private val withHtml = item("HTML")
    private val withBoth = item("BOTH", withNote = true)
    private val all = listOf(withNote, withPdf, withHtml, withBoth)

    private val attachments = mapOf(
        "PDF" to listOf(html("PDF"), pdf("PDF")),
        "HTML" to listOf(html("HTML")),
        "BOTH" to listOf(pdf("BOTH"))
    )
    private val lookup: (String) -> List<Item> = { attachments[it] ?: emptyList() }

    private fun keys(items: List<Item>) = items.map { it.itemKey }

    @Test
    fun `no display filters returns everything in order`() {
        val result = LibraryItemFilter.applyDisplayFilters(all, false, false, lookup)
        assertEquals(keys(all), keys(result))
    }

    @Test
    fun `only notes keeps items that have notes`() {
        val result = LibraryItemFilter.applyDisplayFilters(all, onlyNotes = true, onlyPdfs = false, attachmentsFor = lookup)
        assertEquals(listOf("NOTE", "BOTH"), keys(result))
    }

    @Test
    fun `only pdfs keeps items with at least one pdf attachment`() {
        val result = LibraryItemFilter.applyDisplayFilters(all, onlyNotes = false, onlyPdfs = true, attachmentsFor = lookup)
        assertEquals(listOf("PDF", "BOTH"), keys(result))
    }

    @Test
    fun `both display filters must hold together`() {
        val result = LibraryItemFilter.applyDisplayFilters(all, onlyNotes = true, onlyPdfs = true, attachmentsFor = lookup)
        assertEquals(listOf("BOTH"), keys(result))
    }

    @Test
    fun `query matches title, creator, tag and key case-insensitively`() {
        val items = listOf(
            item("AAAA1111", mapOf("title" to "Discipline and Punish")),
            item("BBBB2222", creators = listOf("Pierre" to "Bourdieu")),
            item("CCCC3333", tags = listOf("governmentality")),
            item("DDDD4444", mapOf("title" to "Unrelated"))
        )
        assertEquals(listOf("AAAA1111"), keys(LibraryItemFilter.matchingQuery(items, "discipline")))
        assertEquals(listOf("BBBB2222"), keys(LibraryItemFilter.matchingQuery(items, "BOURDIEU")))
        assertEquals(listOf("CCCC3333"), keys(LibraryItemFilter.matchingQuery(items, "Government")))
        assertEquals(listOf("DDDD4444"), keys(LibraryItemFilter.matchingQuery(items, "dddd")))
    }

    @Test
    fun `query against an unloaded library is empty`() {
        assertTrue(LibraryItemFilter.matchingQuery(null, "anything").isEmpty())
    }

    @Test
    fun `tag counts span the whole list, sorted by name ignoring case`() {
        val items = listOf(
            item("A", tags = listOf("reading-list", "STS")),
            item("B", tags = listOf("reading-list")),
            item("C", tags = listOf("archive")),
            item("D")
        )
        assertEquals(
            listOf("archive" to 1, "reading-list" to 2, "STS" to 1),
            LibraryItemFilter.tagCounts(items)
        )
    }

    @Test
    fun `tag counts count an item once per tag and handle an unloaded library`() {
        val items = listOf(item("A", tags = listOf("x", "x")))
        assertEquals(listOf("x" to 1), LibraryItemFilter.tagCounts(items))
        assertTrue(LibraryItemFilter.tagCounts(null).isEmpty())
    }

    @Test
    fun `collections match by name case-insensitively`() {
        val collections = listOf(
            ZoteroCollection("K1", 1, "Platform Governance", ""),
            ZoteroCollection("K2", 1, "STS", ""),
            ZoteroCollection("K3", 1, "Governmentality", "K1")
        )
        val result = LibraryItemFilter.collectionsMatching(collections, "GOVERN")
        assertEquals(listOf("K1", "K3"), result.map { it.key })
        assertTrue(LibraryItemFilter.collectionsMatching(null, "x").isEmpty())
    }
}
