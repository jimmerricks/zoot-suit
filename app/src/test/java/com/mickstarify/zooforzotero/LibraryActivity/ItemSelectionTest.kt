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
}
