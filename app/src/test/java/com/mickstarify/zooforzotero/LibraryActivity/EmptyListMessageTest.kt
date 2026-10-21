package com.mickstarify.zooforzotero.LibraryActivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyListMessageTest {
    private val toRead = LibraryFilter(tags = setOf("to-read"))

    @Test
    fun `a filter that hides everything is named and can be cleared`() {
        val m = EmptyListMessage.forList(toRead, filterApplies = true, query = "", collection = "group_all")
        assertEquals("No items match the filter: to-read.", m.text)
        assertTrue(m.canClearFilter)
    }

    @Test
    fun `the filter is not blamed where it does not apply`() {
        val m = EmptyListMessage.forList(toRead, filterApplies = false, query = "", collection = "zooforzotero_Trash")
        assertEquals("The trash is empty.", m.text)
        assertFalse(m.canClearFilter)
    }

    @Test
    fun `search and tag views say what found nothing`() {
        assertEquals(
            "No items match “foucault”.",
            EmptyListMessage.forList(LibraryFilter(), true, "foucault", "all").text
        )
        assertEquals(
            "No items have the tag “method”.",
            EmptyListMessage.forList(LibraryFilter(), true, "tag:method", "all").text
        )
    }

    @Test
    fun `an empty place says which kind`() {
        assertEquals(
            "This collection is empty.",
            EmptyListMessage.forList(LibraryFilter(), true, "", "ABCD1234").text
        )
        assertEquals(
            "This library has no items yet. Pull down to sync.",
            EmptyListMessage.forList(LibraryFilter(), true, "", "all").text
        )
    }
}
