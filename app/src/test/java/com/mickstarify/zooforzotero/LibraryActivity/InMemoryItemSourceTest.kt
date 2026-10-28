package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.SortMethod
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemData
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemTag
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.LoadedLibrary
import org.junit.Assert.assertEquals
import org.junit.Test

class InMemoryItemSourceTest {

    private fun item(
        key: String,
        title: String = key,
        data: Map<String, String> = emptyMap(),
        tags: List<String> = emptyList(),
        collections: List<String> = emptyList(),
    ) = Item().apply {
        itemInfo = ItemInfo(key, version = 1, deleted = false)
        itemData = (mapOf("itemType" to "book", "title" to title) + data)
            .map { (name, value) -> ItemData(key, name, value, "String") }
        creators = emptyList()
        this.tags = tags.map { ItemTag(key, it) }
        this.collections = collections
    }

    private class FakeLibrary(
        override val items: List<Item>,
        override val collections: List<Collection> = emptyList(),
        override val keysWithPdf: Set<String> = emptySet(),
        val trash: List<Item> = emptyList(),
        val publications: List<Item> = emptyList(),
    ) : LoadedLibrary {
        override val allFieldsLoaded = true
        override fun getDisplayableItems() = items.filter { !it.hasParent() }
        override fun getItemsFromCollection(collection: String) = items.filter { collection in it.collections }
        override fun getItemsWithoutCollection() = getDisplayableItems().filter { it.collections.isEmpty() }
        override fun getTrashItems() = trash
        override fun getMyPublications() = publications
        override fun getItemWithKey(itemKey: String) = items.firstOrNull { it.itemKey == itemKey }
    }

    private val cherry = item("C", "Cherry", tags = listOf("to-read"), collections = listOf("COL"))
    private val apple = item("A", "Apple", tags = listOf("to-read", "method"))
    private val banana = item("B", "Banana", collections = listOf("SUB"))
    private val applePdf = item(
        "A-PDF", "apple.pdf",
        data = mapOf("itemType" to "attachment", "contentType" to "application/pdf", "parentItem" to "A")
    )
    private val library = FakeLibrary(
        items = listOf(cherry, apple, banana, applePdf),
        collections = listOf(
            Collection("COL", 1, "Fruit", "false"),
            Collection("SUB", 1, "Yellow", "COL"),
        ),
        keysWithPdf = setOf("A"),
        trash = listOf(item("T2", "Zeta"), item("T1", "Alpha")),
    )

    private var device = DeviceState()
    private val source = InMemoryItemSource({ library }, { device }, { LibrarySearch.Field.entries.toSet() })

    private fun keys(query: LibraryQuery) = source.query(query).map { it.itemKey }

    @Test
    fun `the library lists top-level items sorted by title`() {
        assertEquals(listOf("A", "B", "C"), keys(LibraryQuery(LibraryPlace.Library)))
    }

    @Test
    fun `descending reverses the order`() {
        assertEquals(listOf("C", "B", "A"), keys(LibraryQuery(LibraryPlace.Library, ascending = false)))
    }

    @Test
    fun `tags and has-PDF must all hold`() {
        val toRead = LibraryFilter(tags = setOf("to-read"))
        assertEquals(listOf("A", "C"), keys(LibraryQuery(LibraryPlace.Library, filter = toRead)))
        assertEquals(listOf("A"), keys(LibraryQuery(LibraryPlace.Library, filter = toRead.copy(hasPdf = true))))
    }

    @Test
    fun `search narrows by text`() {
        assertEquals(listOf("B"), keys(LibraryQuery(LibraryPlace.Library, search = "bana")))
    }

    @Test
    fun `a collection, with or without its subcollections`() {
        assertEquals(listOf("C"), keys(LibraryQuery(LibraryPlace.Collection("COL"))))
        assertEquals(
            listOf("B", "C"),
            keys(LibraryQuery(LibraryPlace.Collection("COL"), includeSubcollections = true))
        )
    }

    @Test
    fun `unfiled items are those in no collection`() {
        assertEquals(listOf("A"), keys(LibraryQuery(LibraryPlace.Unfiled)))
    }

    @Test
    fun `on this device matches the parent of a downloaded attachment`() {
        device = DeviceState(onDevice = setOf("A-PDF"))
        assertEquals(listOf("A"), keys(LibraryQuery(LibraryPlace.Library, filter = LibraryFilter(onDevice = true))))
    }

    @Test
    fun `edits to sync include items with metadata edits`() {
        device = DeviceState(itemEdits = setOf("B"))
        assertEquals(listOf("B"), keys(LibraryQuery(LibraryPlace.Library, filter = LibraryFilter(editedHere = true))))
    }

    @Test
    fun `trash ignores search and filter but is sorted`() {
        val query = LibraryQuery(LibraryPlace.Trash, search = "nothing", filter = LibraryFilter(tags = setOf("x")))
        assertEquals(listOf("T1", "T2"), keys(query))
    }

    @Test
    fun `tag counts cover top-level items`() {
        assertEquals(listOf("method" to 1, "to-read" to 2), source.tagCounts())
    }

    @Test
    fun `date sort uses the item's date`() {
        val old = item("OLD", "Zebra", data = mapOf("date" to "1999"))
        val new = item("NEW", "Aardvark", data = mapOf("date" to "2020"))
        val dated = InMemoryItemSource({ FakeLibrary(listOf(new, old)) }, { DeviceState() }, { emptySet() })
        assertEquals(
            listOf("OLD", "NEW"),
            dated.query(LibraryQuery(LibraryPlace.Library, sort = SortMethod.DATE)).map { it.itemKey }
        )
    }
}
