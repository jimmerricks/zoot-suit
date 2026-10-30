package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEdit
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingEditsSummaryTest {
    private val titles = mapOf("BOOK1" to "Discipline and Punish", "BOOK2" to "Distinction")
    private val collections = mapOf("COLL1" to "Theory")

    private fun lines(edits: Map<String, List<ItemEdit>>, files: List<EditedFile> = emptyList()) =
        PendingEditsSummary.lines(edits, files, titles::get, collections::get)

    @Test
    fun anItemsEditsShareOneLine() {
        val edits = mapOf(
            "BOOK1" to listOf(
                ItemEdit(ItemEditKind.ADD_TAG, "to-read"),
                ItemEdit(ItemEditKind.REMOVE_TAG, "unread"),
                ItemEdit(ItemEditKind.ADD_TO_COLLECTION, "COLL1"),
                ItemEdit(ItemEditKind.REMOVE_FROM_COLLECTION, "GONE"),
            )
        )
        assertEquals(
            listOf("Discipline and Punish: tag “to-read” added, tag “unread” removed, added to “Theory”, removed from a collection"),
            lines(edits)
        )
    }

    @Test
    fun newNotesAndEditedFilesGoUnderTheirItem() {
        val edits = mapOf("NOTE1" to listOf(ItemEdit(ItemEditKind.CREATE_NOTE, "BOOK2", "<p>hi</p>")))
        val files = listOf(EditedFile("Full Text PDF", "BOOK2"), EditedFile("Scan.pdf", null))
        assertEquals(
            listOf("Distinction: new note, file “Full Text PDF” edited", "Scan.pdf: file edited"),
            lines(edits, files)
        )
    }

    @Test
    fun anItemNotInTheLibraryIsUntitled() {
        assertEquals(
            listOf("Untitled: tag “x” added"),
            lines(mapOf("MISSING" to listOf(ItemEdit(ItemEditKind.ADD_TAG, "x"))))
        )
    }
}
