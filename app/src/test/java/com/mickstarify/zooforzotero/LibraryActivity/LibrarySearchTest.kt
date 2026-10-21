package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.LibraryActivity.LibrarySearch.Field
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Creator
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemData
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {

    private fun item(
        key: String,
        data: Map<String, String> = emptyMap(),
        creators: List<Pair<String, String>> = emptyList(),
        tags: List<String> = emptyList(),
    ) = Item().apply {
        itemInfo = ItemInfo(key, version = 1, deleted = false)
        itemData = (mapOf("itemType" to "book") + data).map { (name, value) -> ItemData(key, name, value, "String") }
        this.creators = creators.mapIndexed { i, (first, last) -> Creator(key, first, last, "author", i) }
        this.tags = tags.map { ItemTag(key, it) }
        collections = emptyList()
    }

    private val discipline = item(
        "AAAA1111",
        mapOf("title" to "Discipline and Punish", "date" to "1975-00-00 1975", "abstractNote" to "On the prison"),
        creators = listOf("Michel" to "Foucault"),
        tags = listOf("governmentality"),
    )
    private val distinction = item(
        "BBBB2222",
        mapOf("title" to "Distinction", "date" to "1979", "publisher" to "Minuit"),
        creators = listOf("Pierre" to "Bourdieu"),
    )
    init {
        discipline.notes.add(Note().apply { parent = "AAAA1111"; key = "NOTE0001"; note = "<p>Panopticon &amp; power</p>"; tags = emptyList() })
    }
    private val sorlin = item("CCCC3333", mapOf("title" to "Miljöns tid"), creators = listOf("Sverker" to "Sörlin"))
    private val childPdf = item("DDDD4444", mapOf("itemType" to "attachment", "title" to "Discipline PDF", "parentItem" to "AAAA1111"))
    private val library = listOf(discipline, distinction, sorlin, childPdf)

    private fun keys(items: List<Item>) = items.map { it.itemKey }
    private val all = Field.entries.toSet()
    private fun search(query: String, fields: Set<Field> = LibrarySearch.DEFAULT_FIELDS) =
        keys(LibrarySearch.Index(library, fields).search(query))

    @Test
    fun `title, creator and year match, case-insensitively`() {
        assertEquals(listOf("AAAA1111"), search("DISCIPLINE"))
        assertEquals(listOf("BBBB2222"), search("bourdieu"))
        assertEquals(listOf("BBBB2222"), search("pierre"))
        assertEquals(listOf("AAAA1111"), search("1975"))
    }

    @Test
    fun `every term must match, in any field and order`() {
        assertEquals(listOf("AAAA1111"), search("foucault 1975"))
        assertEquals(listOf("AAAA1111"), search("1975 punish"))
        assertEquals(emptyList<String>(), search("foucault 1979"))
        assertEquals(listOf("AAAA1111", "BBBB2222"), search("dis"))
    }

    @Test
    fun `quoted phrases are kept whole`() {
        assertEquals(listOf("AAAA1111"), search("\"and punish\""))
        assertEquals(emptyList<String>(), search("\"punish and\""))
    }

    @Test
    fun `diacritics are ignored on both sides`() {
        assertEquals(listOf("CCCC3333"), search("sorlin"))
        assertEquals(listOf("CCCC3333"), search("Sörlin"))
        assertEquals(listOf("CCCC3333"), search("miljons"))
    }

    @Test
    fun `the default fields leave out other fields, tags, notes and keys`() {
        assertEquals(emptyList<String>(), search("prison"))
        assertEquals(emptyList<String>(), search("minuit"))
        assertEquals(emptyList<String>(), search("governmentality"))
        assertEquals(emptyList<String>(), search("aaaa1111"))
        assertEquals(emptyList<String>(), search("panopticon"))
    }

    @Test
    fun `each field adds its own text`() {
        assertEquals(listOf("AAAA1111"), search("prison", setOf(Field.ABSTRACT)))
        assertEquals(listOf("BBBB2222"), search("minuit", setOf(Field.PUBLICATION)))
        assertEquals(listOf("AAAA1111"), search("govern", setOf(Field.TAGS)))
        assertEquals(listOf("AAAA1111"), search("panopticon & power", setOf(Field.NOTES)))
        assertEquals(listOf("AAAA1111"), search("aaaa1111", setOf(Field.OTHER)))
        assertEquals(emptyList<String>(), search("prison", setOf(Field.PUBLICATION)))
    }

    @Test
    fun `fields can be left out`() {
        assertEquals(emptyList<String>(), search("foucault", setOf(Field.TITLE)))
        assertEquals(emptyList<String>(), search("discipline", setOf(Field.CREATORS)))
        assertEquals(emptyList<String>(), search("1975", setOf(Field.TITLE, Field.CREATORS)))
    }

    @Test
    fun `item type and dates added are never searched`() {
        assertEquals(emptyList<String>(), search("book", all))
    }

    @Test
    fun `child attachments and notes are not results`() {
        assertFalse("DDDD4444" in search("discipline"))
        assertFalse("DDDD4444" in search("pdf", all))
    }

    @Test
    fun `a term can't span two fields`() {
        // title "Distinction" followed by creator "Pierre"
        assertEquals(emptyList<String>(), search("distinctionpierre"))
    }

    @Test
    fun `a blank query finds nothing`() {
        assertEquals(emptyList<String>(), search(""))
        assertEquals(emptyList<String>(), search("   "))
        assertEquals(emptyList<String>(), search("\"\""))
    }

    @Test
    fun `the index is rebuilt for another item list or other fields`() {
        val fields = LibrarySearch.DEFAULT_FIELDS
        val index = LibrarySearch.Index(library, fields)
        assertTrue(index.isFor(library, fields.toSet()))
        assertFalse(index.isFor(library.toList(), fields))
        assertFalse(index.isFor(library, all))
        assertFalse(index.isFor(null, fields))
        assertFalse(index.isFor(library, fields, allFields = false))
    }

    @Test
    fun `an item loaded with the list's fields gets the rest later, all at once`() {
        val partial = item("EEEE5555", mapOf("title" to "Distinction")).apply { hasAllFields = false }
        assertEquals(null, partial.data["abstractNote"])
        assertEquals(emptyList<String>(), keys(LibrarySearch.Index(listOf(partial), setOf(Field.ABSTRACT), false).search("taste")))

        partial.complete(listOf(ItemData("EEEE5555", "abstractNote", "A critique of taste", "String")))
        assertTrue(partial.hasAllFields)
        assertEquals("Distinction", partial.data["title"])
        assertEquals("A critique of taste", partial.getItemData("abstractNote"))
        assertEquals(listOf("EEEE5555"), keys(LibrarySearch.Index(listOf(partial), setOf(Field.ABSTRACT)).search("taste")))

        // a complete item ignores a second batch
        partial.complete(listOf(ItemData("EEEE5555", "abstractNote", "other", "String")))
        assertEquals("A critique of taste", partial.data["abstractNote"])
    }
}
