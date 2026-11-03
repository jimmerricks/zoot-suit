package com.mickstarify.zooforzotero.ZoteroStorage

import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteTextTest {
    @Test
    fun `a note made here reads back as it was typed`() {
        val typed = "First line\nsecond line\n\nA <tag> & \"quote\""
        assertEquals(typed, ItemEdits.noteText(ItemEdits.noteHtml(typed)!!))
    }

    @Test
    fun `a desktop note of paragraphs is plain text`() {
        val html = "<div data-schema-version=\"9\"><p>Read this\n again</p>\n<p>caf&eacute;? no: caf&#233; &amp; more&nbsp;</p>\n</div>"
        assertNull(ItemEdits.noteText(html)) // &eacute; isn't known, so not plain
        val known = "<div data-schema-version=\"9\"><p>Read this\n again</p>\n<p>caf&#233; &amp; more<br/>next</p>\n</div>"
        assertEquals("Read this again\n\ncafé & more\nnext", ItemEdits.noteText(known))
    }

    @Test
    fun `text without markup is one paragraph`() {
        assertEquals("just text", ItemEdits.noteText("just text"))
        assertEquals("", ItemEdits.noteText(""))
    }

    @Test
    fun `formatting the app can't keep makes it not plain`() {
        for (html in listOf(
            "<h1>Title</h1><p>x</p>",
            "<p><strong>bold</strong></p>",
            "<p><a href=\"https://zotero.org\">link</a></p>",
            "<ul><li>one</li></ul>",
            "<p style=\"padding-left: 40px\">indented</p>",
            "<div data-schema-version=\"9\"><p><span class=\"citation\" data-citation=\"x\">(Smith)</span></p></div>",
            "<p><img data-attachment-key=\"ABCD1234\"></p>",
        )) {
            assertNull(html, ItemEdits.noteText(html))
        }
    }

    @Test
    fun `an edit keeps Zotero's wrapper`() {
        val html = "<div data-schema-version=\"9\"><p>old</p></div>"
        assertEquals("<div data-schema-version=\"9\"><p>new<br>line</p></div>", ItemEdits.editedNoteHtml(html, "new\nline"))
        assertEquals("<p>new</p>", ItemEdits.editedNoteHtml("<p>old</p>", "new"))
        assertNull(ItemEdits.editedNoteHtml(html, "  \n "))
    }
}
