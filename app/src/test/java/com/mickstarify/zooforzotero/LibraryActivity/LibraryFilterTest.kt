package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFilterTest {
    private fun item(key: String, vararg tags: String) = Item().apply {
        itemInfo = ItemInfo(key, version = 1, deleted = false)
        itemData = emptyList()
        creators = emptyList()
        this.tags = tags.map { ItemTag(key, it) }
        collections = emptyList()
    }

    private fun LibraryFilter.keeps(
        item: Item, pdf: Set<String> = emptySet(), edited: Set<String> = emptySet(), html: Set<String> = emptySet(),
    ) = matches(item, pdf, html, edited)

    @Test
    fun `an empty filter keeps everything`() {
        assertFalse(LibraryFilter().isActive)
        assertTrue(LibraryFilter().keeps(item("C")))
    }

    @Test
    fun `every selected tag must be present`() {
        val f = LibraryFilter(tags = setOf("theory", "method"))
        assertTrue(f.keeps(item("A", "theory", "method", "other")))
        assertFalse(f.keeps(item("A", "theory")))
    }

    @Test
    fun `on device means the item has that kind of attachment on the device`() {
        val pdf = LibraryFilter(pdfOnDevice = true)
        assertTrue(pdf.keeps(item("B"), pdf = setOf("B")))
        assertFalse(pdf.keeps(item("B"), pdf = setOf("A")))
        assertFalse(pdf.keeps(item("B"), html = setOf("B")))
        val html = LibraryFilter(htmlOnDevice = true)
        assertTrue(html.keeps(item("B"), html = setOf("B")))
        assertFalse(html.keeps(item("B"), pdf = setOf("B")))
        assertTrue(html.isActive && html.needsDeviceState)
    }

    @Test
    fun `tags and device state combine with AND`() {
        val f = LibraryFilter(tags = setOf("theory"), pdfOnDevice = true, editedHere = true)
        assertTrue(f.keeps(item("A", "theory"), pdf = setOf("A"), edited = setOf("A")))
        assertFalse(f.keeps(item("A", "theory"), pdf = setOf("A")))
        assertFalse(f.keeps(item("A"), pdf = setOf("A"), edited = setOf("A")))
    }

    @Test
    fun `has-PDF and has-notes make the filter active and are described`() {
        assertTrue(LibraryFilter(hasPdf = true).isActive)
        assertEquals("has PDF · has notes", LibraryFilter(hasPdf = true, hasNotes = true).describe())
        assertTrue(LibraryFilter(hasHtml = true).isActive)
        assertTrue(LibraryFilter(hasOtherAttachment = true).isActive)
        assertEquals(
            "has HTML · has other attachment",
            LibraryFilter(hasHtml = true, hasOtherAttachment = true).describe()
        )
    }

    @Test
    fun `description lists tags then device conditions`() {
        assertEquals(
            "method · Theory · PDF on this device · HTML on this device",
            LibraryFilter(tags = setOf("Theory", "method"), pdfOnDevice = true, htmlOnDevice = true).describe()
        )
    }
}
