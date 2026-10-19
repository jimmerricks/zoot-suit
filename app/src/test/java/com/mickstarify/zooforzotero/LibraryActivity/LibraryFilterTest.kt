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

    private val attachments = mapOf("A" to listOf("A1"), "B" to listOf("B1", "B2"), "C" to emptyList())
    private val keysOf: (Item) -> Collection<String> = { attachments[it.itemKey] ?: emptyList() }

    private fun LibraryFilter.keeps(item: Item, onDevice: Set<String> = emptySet(), edited: Set<String> = emptySet()) =
        matches(item, keysOf, onDevice, edited)

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
    fun `on device means one of its attachments is on the device`() {
        val f = LibraryFilter(onDevice = true)
        assertTrue(f.keeps(item("B"), onDevice = setOf("B2")))
        assertFalse(f.keeps(item("B"), onDevice = setOf("A1")))
        assertFalse(f.keeps(item("C"), onDevice = setOf("A1")))
    }

    @Test
    fun `tags and device state combine with AND`() {
        val f = LibraryFilter(tags = setOf("theory"), onDevice = true, editedHere = true)
        assertTrue(f.keeps(item("A", "theory"), onDevice = setOf("A1"), edited = setOf("A1")))
        assertFalse(f.keeps(item("A", "theory"), onDevice = setOf("A1")))
        assertFalse(f.keeps(item("A"), onDevice = setOf("A1"), edited = setOf("A1")))
    }

    @Test
    fun `description lists tags then device conditions`() {
        assertEquals(
            "method · Theory · on this device",
            LibraryFilter(tags = setOf("Theory", "method"), onDevice = true).describe()
        )
    }
}
