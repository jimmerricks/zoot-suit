package com.mickstarify.zooforzotero.ZoteroStorage

import com.google.gson.JsonObject
import com.google.gson.Gson
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEdit
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditKind.ADD_TAG
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditKind.CREATE_NOTE
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditKind.REMOVE_TAG
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditKind.SET_FIELD
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemEditsTest {
    private fun data(json: String): JsonObject = Gson().fromJson(json, JsonObject::class.java)

    private fun add(tag: String, id: Long = 0) = ItemEdit(ADD_TAG, tag, id = id)
    private fun remove(tag: String, id: Long = 0) = ItemEdit(REMOVE_TAG, tag, id = id)

    // recording

    @Test
    fun `adding a tag the item has, or removing one it lacks, records nothing`() {
        assertEquals(ItemEdits.Recording.NONE, ItemEdits.record(emptyList(), add("a"), listOf("a"), emptyMap()))
        assertEquals(ItemEdits.Recording.NONE, ItemEdits.record(emptyList(), remove("b"), listOf("a"), emptyMap()))
    }

    @Test
    fun `a new tag edit is inserted`() {
        val r = ItemEdits.record(emptyList(), add("b"), listOf("a"), emptyMap())
        assertEquals(add("b"), r.insert)
        assertTrue(r.delete.isEmpty())
    }

    @Test
    fun `removing a tag added here cancels the add`() {
        val r = ItemEdits.record(listOf(add("b", id = 7)), remove("b"), listOf("a", "b"), emptyMap())
        assertEquals(listOf(7L), r.delete)
        assertNull(r.insert)
    }

    @Test
    fun `while uploading, the opposite edit is appended rather than cancelling`() {
        val r = ItemEdits.record(listOf(add("b", id = 7)), remove("b"), listOf("a", "b"), emptyMap(), fold = false)
        assertTrue(r.delete.isEmpty())
        assertEquals(remove("b"), r.insert)
    }

    @Test
    fun `editing a field twice keeps the first old value`() {
        val first = ItemEdit(SET_FIELD, "title", "B", oldValue = "A", id = 3)
        val r = ItemEdits.record(listOf(first), ItemEdit(SET_FIELD, "title", "C"), emptyList(), mapOf("title" to "B"))
        assertEquals(listOf(3L), r.delete)
        assertEquals(ItemEdit(SET_FIELD, "title", "C", oldValue = "A"), r.insert)
    }

    @Test
    fun `setting a field back to Zotero's value leaves nothing to upload`() {
        val first = ItemEdit(SET_FIELD, "title", "B", oldValue = "A", id = 3)
        val r = ItemEdits.record(listOf(first), ItemEdit(SET_FIELD, "title", "A"), emptyList(), mapOf("title" to "B"))
        assertEquals(listOf(3L), r.delete)
        assertNull(r.insert)
    }

    @Test
    fun `a missing field counts as blank`() {
        val r = ItemEdits.record(emptyList(), ItemEdit(SET_FIELD, "date", "1999"), emptyList(), emptyMap())
        assertEquals(ItemEdit(SET_FIELD, "date", "1999", oldValue = ""), r.insert)
    }

    // applying locally

    @Test
    fun `tag edits apply in order and twice is the same as once`() {
        val edits = listOf(add("b"), remove("a"), add("c"), remove("c"))
        val once = ItemEdits.applyToTags(listOf("a"), edits)
        assertEquals(listOf("b"), once)
        assertEquals(once, ItemEdits.applyToTags(once, edits))
    }

    @Test
    fun `a field set to blank is removed`() {
        val edits = listOf(ItemEdit(SET_FIELD, "date", ""), ItemEdit(SET_FIELD, "title", "T"))
        assertEquals(mapOf("title" to "T"), ItemEdits.applyToFields(mapOf("date" to "1999"), edits))
    }

    @Test
    fun `cleanTag trims and refuses blank or too long tags`() {
        assertEquals("to read", ItemEdits.cleanTag("  to read "))
        assertNull(ItemEdits.cleanTag("   "))
        assertNull(ItemEdits.cleanTag("x".repeat(256)))
    }

    // building the upload

    @Test
    fun `tags Zotero has keep their type and the new one is manual`() {
        val server = data("""{"tags":[{"tag":"auto","type":1},{"tag":"keep"}]}""")
        val patch = ItemEdits.buildPatch(server, listOf(add("new")))
        assertEquals(
            data("""{"tags":[{"tag":"auto","type":1},{"tag":"keep"},{"tag":"new"}]}"""),
            patch.body
        )
    }

    @Test
    fun `tags added in Zotero meanwhile survive a removal here`() {
        val server = data("""{"tags":[{"tag":"old"},{"tag":"added elsewhere"}]}""")
        val patch = ItemEdits.buildPatch(server, listOf(remove("old")))
        assertEquals(data("""{"tags":[{"tag":"added elsewhere"}]}"""), patch.body)
    }

    @Test
    fun `removing a tag removes it whatever its type`() {
        val server = data("""{"tags":[{"tag":"x","type":1},{"tag":"x"}]}""")
        assertEquals(data("""{"tags":[]}"""), ItemEdits.buildPatch(server, listOf(remove("x"))).body)
    }

    @Test
    fun `edits Zotero already has make an empty patch`() {
        val server = data("""{"tags":[{"tag":"a"}],"title":"T"}""")
        val patch = ItemEdits.buildPatch(server, listOf(add("a"), remove("b"), ItemEdit(SET_FIELD, "title", "T", "S")))
        assertEquals(0, patch.body.size())
        assertTrue(patch.conflicts.isEmpty())
    }

    @Test
    fun `a field sends only itself, and tags are left out when untouched`() {
        val server = data("""{"tags":[{"tag":"a"}],"title":"A","date":"1999"}""")
        val patch = ItemEdits.buildPatch(server, listOf(ItemEdit(SET_FIELD, "title", "B", oldValue = "A")))
        assertEquals(data("""{"title":"B"}"""), patch.body)
    }

    @Test
    fun `a field changed in Zotero too is a conflict and not sent`() {
        val server = data("""{"title":"changed elsewhere"}""")
        val edit = ItemEdit(SET_FIELD, "title", "B", oldValue = "A")
        val patch = ItemEdits.buildPatch(server, listOf(edit, add("t")))
        assertEquals(listOf(edit), patch.conflicts)
        assertFalse(patch.body.has("title"))
        assertTrue(patch.body.has("tags"))
    }

    @Test
    fun `an item without tags in Zotero gets the added one`() {
        assertEquals(data("""{"tags":[{"tag":"t"}]}"""), ItemEdits.buildPatch(data("{}"), listOf(add("t"))).body)
    }

    // new notes

    @Test
    fun `a new item key is eight of Zotero's key characters`() {
        repeat(100) { assertTrue(ItemEdits.newItemKey().matches(Regex("[23456789ABCDEFGHIJKLMNPQRSTUVWXYZ]{8}"))) }
    }

    @Test
    fun `a typed note becomes paragraphs with line breaks, escaped`() {
        assertEquals("<p>one</p>", ItemEdits.noteHtml("  one \n"))
        assertEquals("<p>a<br>b</p><p>c &amp; &lt;d&gt;</p>", ItemEdits.noteHtml("a\n b\n\n \nc & <d>"))
        assertNull(ItemEdits.noteHtml(" \n\n "))
    }

    @Test
    fun `a new note is always recorded`() {
        val edit = ItemEdit(CREATE_NOTE, "PARENT22", "<p>x</p>")
        assertEquals(ItemEdits.Recording(insert = edit), ItemEdits.record(emptyList(), edit, emptyList(), emptyMap()))
    }

    @Test
    fun `a new note is created under its own key at version 0`() {
        val json = ItemEdits.newNoteJson("NOTE2345", ItemEdit(CREATE_NOTE, "PARENT22", "<p>x</p>"))
        assertEquals(
            data("""{"key":"NOTE2345","version":0,"itemType":"note","parentItem":"PARENT22","note":"<p>x</p>","tags":[],"collections":[],"relations":{}}"""),
            json
        )
    }
}
