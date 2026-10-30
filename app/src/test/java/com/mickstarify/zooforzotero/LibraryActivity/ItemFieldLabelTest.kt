package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.LibraryActivity.ItemView.ItemFieldLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ItemFieldLabelTest {

    @Test
    fun `camel case keys become title case words`() {
        assertEquals("Title", ItemFieldLabel.of("title"))
        assertEquals("Library Catalog", ItemFieldLabel.of("libraryCatalog"))
        assertEquals("Journal Article", ItemFieldLabel.of("journalArticle"))
    }

    @Test
    fun `Zotero's own names and acronyms win`() {
        assertEquals("Accessed", ItemFieldLabel.of("accessDate"))
        assertEquals("Abstract", ItemFieldLabel.of("abstractNote"))
        assertEquals("URL", ItemFieldLabel.of("url"))
        assertEquals("DOI", ItemFieldLabel.of("DOI"))
    }

    @Test
    fun `creators read last name first`() {
        assertEquals("Törnberg, Petter", ItemFieldLabel.creatorName("Petter", "Törnberg"))
        assertEquals("World Health Organization", ItemFieldLabel.creatorName("", "World Health Organization"))
        assertEquals("", ItemFieldLabel.creatorName(null, null))
    }

    private val uk = Locale.UK
    private val stockholm = TimeZone.getTimeZone("Europe/Stockholm")

    @Test
    fun `timestamps show in the reader's zone and format`() {
        // CET after the clocks went back on 25 October: UTC+1
        val added = ItemFieldLabel.value("dateAdded", "2026-10-30T01:23:36Z", uk, stockholm)
        assertTrue(added, added.startsWith("30 Oct 2026") && added.endsWith("02:23"))
        val accessed = ItemFieldLabel.value("accessDate", "2026-07-01 12:00:00", uk, stockholm)
        assertTrue(accessed, accessed.startsWith("1 Jul 2026") && accessed.endsWith("14:00"))
    }

    @Test
    fun `other values and unreadable timestamps stay as they are`() {
        assertEquals("2026-10-30", ItemFieldLabel.value("date", "2026-10-30", uk, stockholm))
        assertEquals("2026-10-30", ItemFieldLabel.value("accessDate", "2026-10-30", uk, stockholm))
    }
}
