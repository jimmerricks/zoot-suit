package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.LibraryActivity.BulkTagChoices.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkTagChoicesTest {
    // 4 items: "all" on each, "some" on 2
    private fun choices() = BulkTagChoices(4, mapOf("all" to 4, "some" to 2))

    @Test
    fun `tags start as they are on the items`() {
        val c = choices()
        assertEquals(State.ALL, c.state("all"))
        assertEquals(State.SOME, c.state("some"))
        assertEquals(State.NONE, c.state("other"))
        assertFalse(c.hasChanges)
    }

    @Test
    fun `a tag on all of them goes off and back`() {
        val c = choices()
        c.tap("all")
        assertEquals(State.NONE, c.state("all"))
        assertEquals(listOf("all"), c.toRemove)
        assertEquals(4, c.affected("all"))
        c.tap("all")
        assertEquals(State.ALL, c.state("all"))
        assertFalse(c.hasChanges)
    }

    @Test
    fun `a tag on some of them goes on all, off all, then back to some`() {
        val c = choices()
        c.tap("some")
        assertEquals(State.ALL, c.state("some"))
        assertEquals(listOf("some"), c.toAdd)
        assertEquals(2, c.affected("some"))
        c.tap("some")
        assertEquals(State.NONE, c.state("some"))
        assertEquals(listOf("some"), c.toRemove)
        assertEquals(2, c.affected("some"))
        c.tap("some")
        assertEquals(State.SOME, c.state("some"))
        assertFalse(c.isChanged("some"))
        assertTrue(c.toAdd.isEmpty() && c.toRemove.isEmpty())
    }

    @Test
    fun `a typed tag is listed and goes on all of them, and off again`() {
        val c = choices()
        c.add("new")
        assertEquals(setOf("all", "some", "new"), c.tags())
        assertEquals(State.ALL, c.state("new"))
        assertEquals(4, c.affected("new"))
        c.tap("new")
        assertEquals(State.NONE, c.state("new"))
        assertFalse(c.hasChanges)
        // still listed, to tick again
        assertTrue("new" in c.tags())
    }

    @Test
    fun `adding a tag all of them have changes nothing, adding one some have fills it in`() {
        val c = choices()
        c.add("all")
        assertFalse(c.hasChanges)
        c.add("some")
        assertEquals(listOf("some"), c.toAdd)
    }

    @Test
    fun `adds and removes keep the order they were chosen in`() {
        val c = choices()
        c.add("b")
        c.tap("all")
        c.add("a")
        assertEquals(listOf("b", "a"), c.toAdd)
        assertEquals(listOf("all"), c.toRemove)
    }
}
