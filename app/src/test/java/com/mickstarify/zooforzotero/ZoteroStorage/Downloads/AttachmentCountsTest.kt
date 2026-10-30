package com.mickstarify.zooforzotero.ZoteroStorage.Downloads

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemData
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AttachmentCountsTest {
    private fun attachment(key: String, contentType: String, filename: String, linkMode: String = "imported_file") =
        Item().apply {
            itemInfo = ItemInfo(key, version = 1, deleted = false)
            itemData = listOf(
                ItemData(key, "itemType", "attachment", "String"),
                ItemData(key, "contentType", contentType, "String"),
                ItemData(key, "filename", filename, "String"),
                ItemData(key, "linkMode", linkMode, "String"),
            )
            creators = emptyList()
            tags = emptyList()
            collections = emptyList()
        }

    private val pdf1 = attachment("PDF12222", "application/pdf", "a.pdf")
    private val pdf2 = attachment("PDF22222", "application/pdf", "b.pdf")
    private val html = attachment("HTML2222", "text/html", "page.html", "imported_url")
    private val epub = attachment("EPUB2222", "application/epub+zip", "book.epub")
    private val linked = attachment("LINK2222", "application/pdf", "c.pdf", "linked_file")

    @Test
    fun `each kind counts its stored files, split by whether they are here`() {
        val counts = AttachmentCounts.of(listOf(pdf1, pdf2, html, epub, linked, pdf1)) { it.itemKey == "PDF22222" }
        assertEquals(listOf("PDF12222"), counts.getValue(AttachmentKind.PDF).missing.map { it.itemKey })
        assertEquals(listOf("PDF22222"), counts.getValue(AttachmentKind.PDF).onDevice.map { it.itemKey })
        assertEquals(listOf("HTML2222"), counts.getValue(AttachmentKind.HTML).missing.map { it.itemKey })
        // the linked file isn't stored in Zotero; pdf1 is counted once
        assertEquals(4, counts.getValue(AttachmentKind.ALL).total)
        assertEquals(3, counts.getValue(AttachmentKind.ALL).missing.size)
    }

    @Test
    fun `kinds without files are left out`() {
        val counts = AttachmentCounts.of(listOf(epub)) { false }
        assertEquals(setOf(AttachmentKind.ALL), counts.keys)
        assertFalse(AttachmentKind.isStoredFile(linked))
    }
}
