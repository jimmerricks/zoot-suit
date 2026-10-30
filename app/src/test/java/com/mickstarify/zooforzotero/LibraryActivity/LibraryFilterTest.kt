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

    private fun LibraryFilter.keeps(item: Item, onDevice: Set<String> = emptySet(), edited: Set<String> = emptySet()) =
        matches(item, onDevice, edited)

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
    fun `on device means the item has an attachment on the device`() {
        val f = LibraryFilter(onDevice = true)
        assertTrue(f.keeps(item("B"), onDevice = setOf("B")))
        assertFalse(f.keeps(item("B"), onDevice = setOf("A")))
    }

    @Test
    fun `tags and device state combine with AND`() {
        val f = LibraryFilter(tags = setOf("theory"), onDevice = true, editedHere = true)
        assertTrue(f.keeps(item("A", "theory"), onDevice = setOf("A"), edited = setOf("A")))
        assertFalse(f.keeps(item("A", "theory"), onDevice = setOf("A")))
        assertFalse(f.keeps(item("A"), onDevice = setOf("A"), edited = setOf("A")))
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
            "method · Theory · on this device",
            LibraryFilter(tags = setOf("Theory", "method"), onDevice = true).describe()
        )
    }
}
