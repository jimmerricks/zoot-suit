package com.mickstarify.zooforzotero.LibraryActivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemSelectionTest {
    private val listed = listOf("A", "B", "C", "D")

    private fun selection(vararg keys: String) = ItemSelection().apply { keys.forEach { toggle(it) } }

    @Test
    fun `toggling selects, and selects off again`() {
        val s = selection("B", "A")
        assertEquals(listOf("B", "A"), s.keys())
        s.toggle("B")
        assertEquals(listOf("A"), s.keys())
        assertTrue("A" in s)
        assertFalse("B" in s)
    }

    @Test
    fun `select all keeps the earlier choice first and adds the rest in list order`() {
        val s = selection("C")
        s.selectAll(listed)
        assertEquals(listOf("C", "A", "B", "D"), s.keys())
    }

    @Test
    fun `invert swaps within the list`() {
        val s = selection("A", "C")
        s.invert(listed)
        assertEquals(listOf("B", "D"), s.keys())
        s.invert(listed)
        assertEquals(listOf("A", "C"), s.keys())
    }

    @Test
    fun `invert of nothing is all, of all is nothing`() {
        val s = ItemSelection()
        s.invert(listed)
        assertEquals(listed, s.keys())
        s.invert(listed)
        assertTrue(s.isEmpty())
    }

    @Test
    fun `a new list drops what it no longer shows`() {
        val s = selection("A", "C", "D")
        s.retainIn(listOf("D", "C", "E"))
        assertEquals(listOf("C", "D"), s.keys())
        assertEquals(2, s.size)
    }

    @Test
    fun `clear empties it and stays in selection mode`() {
        val s = selection("A", "B")
        s.clear()
        assertTrue(s.isEmpty())
        assertTrue(s.active)
    }

    @Test
    fun `unticking the last item stays in selection mode`() {
        val s = selection("A")
        s.toggle("A")
        assertTrue(s.isEmpty())
        assertTrue(s.active)
    }

    @Test
    fun `end leaves selection mode`() {
        val s = selection("A", "B")
        s.end()
        assertTrue(s.isEmpty())
        assertFalse(s.active)
        assertFalse(ItemSelection().active)
    }

    private val list = listOf("A", "B", "C", "D", "E", "F")

    @Test
    fun `a range selects both ends and what is between, either way round`() {
        val down = selection("B")
        down.selectRange("E", list)
        assertEquals(setOf("B", "C", "D", "E"), down.keys().toSet())
        val up = selection("E")
        up.selectRange("B", list)
        assertEquals(setOf("B", "C", "D", "E"), up.keys().toSet())
    }

    @Test
    fun `a range from a ticked item keeps what was selected in it and chains on`() {
        val s = selection("C", "A") // A pressed last, ticked
        s.selectRange("B", list)
        assertEquals(setOf("A", "B", "C"), s.keys().toSet())
        s.selectRange("E", list) // from B, the end of the last range
        assertEquals(setOf("A", "B", "C", "D", "E"), s.keys().toSet())
    }

    @Test
    fun `a range from an unticked item unselects, both ends included, and chains on`() {
        val s = ItemSelection()
        s.selectAll(list)
        s.toggle("E") // unticked: the ranges from here unselect
        s.selectRange("C", list)
        assertEquals(setOf("A", "B", "F"), s.keys().toSet())
        s.selectRange("B", list) // from C, unselected
        assertEquals(setOf("A", "F"), s.keys().toSet())
    }

    @Test
    fun `without an item pressed before a range is just the item`() {
        val s = selection("A")
        s.clear()
        s.selectRange("D", list)
        assertEquals(listOf("D"), s.keys())
        val gone = selection("A")
        gone.retainIn(listOf("C", "D", "E"))
        gone.selectRange("E", listOf("C", "D", "E"))
        assertEquals(listOf("E"), gone.keys())
    }
}
