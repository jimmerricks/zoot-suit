package com.mickstarify.zooforzotero.SyncSetup.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignOutTest {
    @Test
    fun `nothing waiting says nothing`() {
        val none = SignOut.UnsyncedEdits(0, 0, 0)
        assertTrue(none.isEmpty)
        assertEquals(emptyList<String>(), none.describe())
    }

    @Test
    fun `each kind of edit is named with its count`() {
        val some = SignOut.UnsyncedEdits(newNotes = 2, editedItems = 1, editedAttachments = 3)
        assertFalse(some.isEmpty)
        assertEquals(
            listOf("2 new notes", "tag changes on 1 item", "3 edited attachments not uploaded"),
            some.describe()
        )
    }

    @Test
    fun `one of a kind is singular and kinds without edits are left out`() {
        assertEquals(
            listOf("1 new note", "1 edited attachment not uploaded"),
            SignOut.UnsyncedEdits(newNotes = 1, editedItems = 0, editedAttachments = 1).describe()
        )
    }
}
