package com.mickstarify.zooforzotero.LibraryActivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDialogTest {
    @Test
    fun `no edits, no lines`() {
        assertEquals("", SyncDialog.editLines(emptyList()))
    }

    @Test
    fun `a line per item`() {
        assertEquals(
            "• Book: tag “a” added\n• Paper: new note",
            SyncDialog.editLines(listOf("Book: tag “a” added", "Paper: new note")),
        )
    }

    @Test
    fun `thousands of edits are cut short`() {
        val text = SyncDialog.editLines((1..2500).map { "Item $it" })
        assertEquals(SyncDialog.MAX_EDIT_LINES, text.lines().count { it.startsWith("• ") })
        assertTrue(text.endsWith("…and 2,400 more items."))
    }
}
