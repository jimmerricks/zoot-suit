package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryNavigatorTest {

    private fun group(id: Int) =
        GroupInfo(id, -1, "group$id", "", "", "", "", "", "", -1)

    @Test
    fun `starts with a single root state`() {
        val nav = LibraryNavigator()
        assertEquals(1, nav.states.size)
        assertEquals("unset", nav.currentCollection())
        assertFalse(nav.isUsingGroup())
    }

    @Test
    fun `setCollection pushes a new state and becomes current`() {
        val nav = LibraryNavigator()
        val pushed = nav.setCollection("all")
        assertTrue(pushed)
        assertEquals(2, nav.states.size)
        assertEquals("all", nav.currentCollection())
    }

    @Test
    fun `setCollection is a no-op when already on that collection`() {
        val nav = LibraryNavigator()
        nav.setCollection("all")
        val pushedAgain = nav.setCollection("all")
        assertFalse(pushedAgain)
        assertEquals(2, nav.states.size)
    }

    @Test
    fun `re-opening the same collection from a filtered view clears the filter`() {
        val nav = LibraryNavigator()
        nav.setCollection("all")
        nav.current.filterText = "tag:to-read"
        assertTrue(nav.setCollection("all"))
        assertEquals("", nav.current.filterText)
        assertEquals("all", nav.currentCollection())
        assertEquals("tag:to-read", nav.states[nav.states.size - 2].filterText)
    }

    @Test
    fun `opening a collection leaves the current filter behind`() {
        val nav = LibraryNavigator()
        nav.current.filterText = "tag:to-read"
        nav.setCollection("all")
        assertEquals("", nav.current.filterText)
        assertEquals("tag:to-read", nav.states[nav.states.size - 2].filterText)
    }

    @Test
    fun `opening a collection stays in the current group`() {
        val nav = LibraryNavigator()
        nav.current.currentGroup = group(42)

        nav.setCollection("ABCD1234")

        assertTrue(nav.isUsingGroup())
        assertEquals(42, nav.currentGroup().id)
    }

    @Test
    fun `opening My Library drops the current group`() {
        val nav = LibraryNavigator()
        nav.current.currentGroup = group(42)
        nav.current.currentCollection = "ABCD1234"

        assertTrue(nav.openLibrary(null))

        assertFalse(nav.isUsingGroup())
        assertEquals("all", nav.currentCollection())
    }

    @Test
    fun `opening a group shows its top`() {
        val nav = LibraryNavigator()
        nav.setCollection("all")

        assertTrue(nav.openLibrary(group(42)))

        assertEquals(42, nav.currentGroup().id)
        assertEquals("group_all", nav.currentCollection())
    }

    @Test
    fun `opening the library already shown is a no-op`() {
        val nav = LibraryNavigator()
        nav.openLibrary(group(42))
        assertFalse(nav.openLibrary(group(42)))
        assertTrue(nav.openLibrary(group(7)))
        assertEquals(3, nav.states.size)
    }
}
