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
        item("$parent-PDF", mapOf("itemType" to "attachment", "contentType" to "application/pdf", "parentItem" to parent))

    private fun html(parent: String) =
        item("$parent-HTML", mapOf("itemType" to "attachment", "contentType" to "text/html", "parentItem" to parent))

    private val withNote = item("NOTE", withNote = true)
    private val withPdf = item("PDF")
    private val withHtml = item("HTML")
    private val withBoth = item("BOTH", withNote = true)
    private val all = listOf(withNote, withPdf, withHtml, withBoth)

    private val keysWithPdf = setOf("PDF", "BOTH")

    private fun keys(items: List<Item>) = items.map { it.itemKey }

    @Test
    fun `no display filters returns everything in order`() {
        val result = LibraryItemFilter.applyDisplayFilters(all, false, false, keysWithPdf)
        assertEquals(keys(all), keys(result))
    }

    @Test
    fun `only notes keeps items that have notes`() {
        val result = LibraryItemFilter.applyDisplayFilters(all, onlyNotes = true, onlyPdfs = false, keysWithPdf = keysWithPdf)
        assertEquals(listOf("NOTE", "BOTH"), keys(result))
    }

    @Test
    fun `only pdfs keeps items with a pdf`() {
        val result = LibraryItemFilter.applyDisplayFilters(all, onlyNotes = false, onlyPdfs = true, keysWithPdf = keysWithPdf)
        assertEquals(listOf("PDF", "BOTH"), keys(result))
    }

    @Test
    fun `a pdf attachment belongs to its parent, or to itself if standalone`() {
        assertEquals("PDF", pdf("PDF").pdfOwnerKey())
        assertEquals("ALONE", item("ALONE", mapOf("itemType" to "attachment", "contentType" to "application/pdf")).pdfOwnerKey())
        assertEquals(null, html("HTML").pdfOwnerKey())
        assertEquals(null, item("BOOK", mapOf("itemType" to "book")).pdfOwnerKey())
    }

    @Test
    fun `both display filters must hold together`() {
        val result = LibraryItemFilter.applyDisplayFilters(all, onlyNotes = true, onlyPdfs = true, keysWithPdf = keysWithPdf)
        assertEquals(listOf("BOTH"), keys(result))
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
}
