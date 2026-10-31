package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Creator
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemData
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItemSubtitleTest {
    private fun item(vararg fields: Pair<String, String>, creators: List<String> = listOf("Haraway")) = Item().apply {
        itemInfo = ItemInfo("K", version = 1, deleted = false)
        itemData = fields.map { (name, value) -> ItemData("K", name, value, "String") }
        this.creators = creators.mapIndexed { i, last -> Creator("K", "", last, "author", i) }
        tags = emptyList()
        collections = emptyList()
    }

    private val article = item(
        "itemType" to "journalArticle", "date" to "1988-09-01", "publicationTitle" to "Feminist Studies",
    )

    @Test
    fun `creator only is the line as it was`() {
        assertEquals("Haraway", ItemSubtitle.CREATOR.of(article))
    }

    @Test
    fun `year and publication follow the creator`() {
        assertEquals("Haraway · 1988", ItemSubtitle.CREATOR_YEAR.of(article))
        assertEquals("Haraway · 1988 · Feminist Studies", ItemSubtitle.CREATOR_YEAR_PUBLICATION.of(article))
    }

    @Test
    fun `missing parts are left out, separators too`() {
        val bare = item("itemType" to "document", creators = emptyList())
        assertEquals("", ItemSubtitle.CREATOR_YEAR_PUBLICATION.of(bare))
        val noCreator = item("itemType" to "report", "date" to "2020", creators = emptyList())
        assertEquals("2020", ItemSubtitle.CREATOR_YEAR_PUBLICATION.of(noCreator))
        val twoAuthors = item("itemType" to "book", creators = listOf("Latour", "Woolgar"))
        assertEquals("Latour et al.", ItemSubtitle.CREATOR_YEAR.of(twoAuthors))
    }

    @Test
    fun `the year is the first four digits standing alone`() {
        assertEquals("2019", ItemSubtitle.year("2019-03-01"))
        assertEquals("1979", ItemSubtitle.year("March 1979"))
        assertEquals("1999", ItemSubtitle.year("1999/2000"))
        assertNull(ItemSubtitle.year("n.d."))
        assertNull(ItemSubtitle.year("12345"))
        assertNull(ItemSubtitle.year(null))
    }

    @Test
    fun `a chapter shows its book, a book its publisher`() {
        val chapter = item("itemType" to "bookSection", "bookTitle" to "Simians, Cyborgs, and Women", "publisher" to "Routledge")
        assertEquals("Simians, Cyborgs, and Women", ItemSubtitle.publication(chapter))
        val book = item("itemType" to "book", "publisher" to "Routledge")
        assertEquals("Routledge", ItemSubtitle.publication(book))
        val thesis = item("itemType" to "thesis", "university" to "Lund University")
        assertEquals("Lund University", ItemSubtitle.publication(thesis))
    }

    @Test
    fun `an unknown stored value reads as creator`() {
        assertEquals(ItemSubtitle.CREATOR, ItemSubtitle.fromStored(null))
        assertEquals(ItemSubtitle.CREATOR, ItemSubtitle.fromStored("bogus"))
        assertEquals(ItemSubtitle.CREATOR_YEAR, ItemSubtitle.fromStored("creator_year"))
    }
}
