package com.mickstarify.zooforzotero.LibraryActivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmptyListMessageTest {
    private val toRead = LibraryFilter(tags = setOf("to-read"))
    private val none = LibraryFilter()

    @Test
    fun `a filter that hides everything is named and can be cleared`() {
        val m = EmptyListMessage.forList("", toRead, filterApplies = true, collection = "group_all")
        assertEquals("No items match the filter: to-read.", m.text)
        assertEquals("Clear filter", m.clearLabel)
    }

    @Test
    fun `a search that finds nothing here is named and can be cleared`() {
        val m = EmptyListMessage.forList("foucault", none, filterApplies = true, collection = "ABCD1234")
        assertEquals("No items here match “foucault”.", m.text)
        assertEquals("Clear search", m.clearLabel)
    }

    @Test
    fun `search and filter together are named together and cleared together`() {
        val m = EmptyListMessage.forList("foucault", toRead, filterApplies = true, collection = "all")
        assertEquals("No items match “foucault” with the filter: to-read.", m.text)
        assertEquals("Clear search and filter", m.clearLabel)
    }

    @Test
    fun `search and filter are not blamed where they do not apply`() {
        val m = EmptyListMessage.forList("foucault", toRead, filterApplies = false, collection = "zooforzotero_Trash")
        assertEquals("The trash is empty.", m.text)
        assertNull(m.clearLabel)
    }

    @Test
    fun `an empty place says which kind`() {
        assertEquals("This collection is empty.", EmptyListMessage.forList("", none, true, "ABCD1234").text)
        assertEquals(
            "This library has no items yet. Pull down to sync.",
            EmptyListMessage.forList("", none, true, "all").text
        )
    }
}
