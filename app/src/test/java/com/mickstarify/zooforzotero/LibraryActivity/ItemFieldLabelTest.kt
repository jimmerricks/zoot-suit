package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.LibraryActivity.ItemView.ItemFieldLabel
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
