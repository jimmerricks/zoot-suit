package com.mickstarify.zooforzotero.LibraryActivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDialogTest {
    @Test
    fun `without edits it is the sync alone`() {
        assertEquals("Last finished sync: today", SyncDialog.message(null, "Last finished sync: today"))
        assertEquals("Items: up to date", SyncDialog.message(emptyList(), "Items: up to date"))
    }

    @Test
    fun `edits come first, then the sync`() {
        assertEquals(
            "Waiting to sync:\n• Book: tag “a” added\n• Paper: new note\n\nNot synced before",
            SyncDialog.message(listOf("Book: tag “a” added", "Paper: new note"), "Not synced before"),
        )
    }

    @Test
    fun `thousands of edits are cut short`() {
        val text = SyncDialog.message((1..2500).map { "Item $it" }, "Not synced before")
        assertEquals(SyncDialog.MAX_EDIT_LINES, text.lines().count { it.startsWith("• ") })
        assertTrue(text.contains("…and 2,400 more items."))
    }
}
