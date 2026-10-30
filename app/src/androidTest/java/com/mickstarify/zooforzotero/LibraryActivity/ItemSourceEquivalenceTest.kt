package com.mickstarify.zooforzotero.LibraryActivity

import androidx.test.platform.app.InstrumentationRegistry
import com.mickstarify.zooforzotero.SortMethod
import com.mickstarify.zooforzotero.ZoteroAPI.Model.CreatorPOJO
import com.mickstarify.zooforzotero.ZoteroAPI.Model.ItemPOJO
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEdit
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditKind
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditStore
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.ZoteroDB
import com.mickstarify.zooforzotero.di.SingletonComponentsEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * The list read from the database (RoomItemSource) must show what the list built from the
 * whole library in memory (InMemoryItemSource over ZoteroDB) shows, for every kind of
 * query. A generated library with the awkward cases (children of several kinds, trashed
 * items and children, standalone attachments, blank notes, tags differing only in case,
 * nested collections, accents, abstracts with and without a folded copy) is written to the app's database under a group id of its
 * own, loaded both ways, and asked the same questions.
 */
class ItemSourceEquivalenceTest {
    private val group = 777001
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database: ZoteroDatabase =
        EntryPointAccessors.fromApplication(context, SingletonComponentsEntryPoint::class.java).getZoteroDatabase()

    private lateinit var memory: InMemoryItemSource
    private lateinit var room: RoomItemSource
    private lateinit var collections: List<Collection>
    private var device = DeviceState()
    private var fields = LibrarySearch.DEFAULT_FIELDS
    private val attachmentKeys = mutableListOf<String>()
    private val topKeys = mutableListOf<String>()

    private val words = listOf("governance", "platform", "labour", "Sörlin", "archive", "power", "memory", "Method")
    private val tagPool = listOf("to-read", "method", "Method", "archive", "théorie", "x")

    // abstracts SQL folds with lower() and abstracts that need a folded copy (SearchText)
    private val abstracts = listOf<(String) -> String>(
        { "abstract about $it" }, { "Abstract About ${it.uppercase()}" }, { "ÉTAT and $it" },
        { "théorie du $it" }, { "Øresund — “$it”" }, { "Straße of $it" }, { "東京 $it" }, { "naïve $it" },
        { "≠ $it" }, { "" },
    )

    @Before
    fun setUp() {
        clear()
        val random = Random(42)
        val cols = mutableListOf<Collection>()
        for (top in 0 until 4) {
            cols += Collection("COLT$top", 1, "Top $top", "false", group)
            for (sub in 0 until 2) {
                cols += Collection("COLS${top}$sub", 1, "Sub $top.$sub", "COLT$top", group)
                cols += Collection("COLD${top}$sub", 1, "Deep $top.$sub", "COLS${top}$sub", group)
            }
        }
        database.writeCollections(cols).blockingAwait()

        val pojos = mutableListOf<ItemPOJO>()
        var n = 0
        fun key() = "EQ%06d".format(n++)
        fun pojo(
            key: String, data: Map<String, String>, tags: List<String> = emptyList(),
            creators: List<CreatorPOJO> = emptyList(), collections: List<String> = emptyList(), deleted: Boolean = false,
        ) = ItemPOJO(key, 1, data + ("key" to key), tags, creators, collections, deleted = if (deleted) 1 else 0)

        for (i in 0 until 300) {
            val key = key()
            val type = listOf("book", "journalArticle", "case", "statute", "note", "attachment")[random.nextInt(6)]
            val title = if (random.nextInt(10) == 0) "Same title" else
                (1..3).joinToString(" ") { words[random.nextInt(words.size)] } + " $i"
            val data = mutableMapOf("itemType" to type, "dateAdded" to "2020-01-%02dT00:00:00Z".format(1 + i % 28))
            when (type) {
                "case" -> data["caseName"] = title
                "statute" -> data["nameOfAct"] = title
                "note" -> data["note"] = "<p>$title</p>"
                "attachment" -> {
                    data["title"] = title
                    data["linkMode"] = "imported_file"
                    data["contentType"] = if (random.nextBoolean()) "application/pdf" else "text/html"
                    data["filename"] = "$key.${if (data["contentType"] == "application/pdf") "pdf" else "html"}"
                    attachmentKeys += key
                }
                else -> data["title"] = title
            }
            if (random.nextInt(3) == 0) data["date"] = (1950 + random.nextInt(70)).toString()
            if (random.nextInt(15) == 0) data["inPublications"] = "true"
            if (random.nextInt(8) == 0) data["abstractNote"] = abstracts[i % abstracts.size](words[random.nextInt(words.size)])
            val creators = (0 until random.nextInt(4)).map {
                CreatorPOJO("author", "First$it", words[random.nextInt(words.size)])
            }.let { if (random.nextInt(25) == 0 && it.isNotEmpty()) it + it.first() else it } // a creator twice
            val tags = tagPool.filter { random.nextInt(4) == 0 }
            val inCollections = cols.filter { random.nextInt(12) == 0 }.map { it.key }
            val trashed = random.nextInt(20) == 0
            pojos += pojo(key, data, tags, creators, inCollections, trashed)
            if (!trashed) topKeys += key

            if (type == "note" || type == "attachment") continue
            for (c in 0 until random.nextInt(4)) {
                val child = key()
                val childTrashed = random.nextInt(10) == 0
                pojos += when (random.nextInt(4)) {
                    0 -> pojo(child, mapOf("itemType" to "note", "parentItem" to key, "note" to "<p>note ${words[random.nextInt(words.size)]}</p>"), deleted = childTrashed)
                    1 -> pojo(child, mapOf("itemType" to "note", "parentItem" to key), deleted = childTrashed) // blank note: not stored, doesn't parse
                    else -> {
                        attachmentKeys += child
                        // PDFs, HTML snapshots, other files (EPUB, no type at all), web links
                        val (type, linkMode) = listOf(
                            "application/pdf" to "imported_file", "application/pdf" to "imported_file",
                            "text/html" to "imported_url", "application/epub+zip" to "imported_file",
                            null to "imported_file", "text/html" to "linked_url",
                        )[random.nextInt(6)]
                        val fields = mutableMapOf(
                            "itemType" to "attachment", "parentItem" to key, "linkMode" to linkMode,
                            "title" to "Full Text", "filename" to "$child.file",
                        )
                        type?.let { fields["contentType"] = it }
                        pojo(child, fields, deleted = childTrashed)
                    }
                }
            }
        }
        // in batches, as a sync writes them: one chain of a whole library overflows the stack
        for (batch in pojos.chunked(50)) database.writeItemPOJOs(group, batch).blockingAwait()

        val zoteroDB = ZoteroDB(context, group)
        zoteroDB.loadCollectionsFromDatabase().blockingAwait()
        zoteroDB.loadItemsFromDatabase().blockingAwait()
        zoteroDB.loadTrashItemsFromDB().blockingAwait()
        // the fields left out at load (abstracts, …) arrive in the background
        val deadline = System.currentTimeMillis() + 20_000
        while (!zoteroDB.allFieldsLoaded && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("all fields loaded", zoteroDB.allFieldsLoaded)
        collections = zoteroDB.collections!!
        memory = InMemoryItemSource({ zoteroDB }, { device }, { fields })
        room = RoomItemSource(database, { group }, { collections }, { device }, { fields })
    }

    @After
    fun tearDown() = clear()

    private fun clear() {
        database.deleteAllItemsForGroup(group).blockingAwait()
        for (c in database.getCollections(group).blockingGet() ?: emptyList()) database.deleteCollection(c.key).blockingAwait()
    }

    /** What a row shows and the item dialog reads: key, title, author, PDF, attachments, notes. */
    private fun rows(items: List<Item>) = items.map {
        listOf(it.itemKey, it.getTitle(), it.getAuthor(), it.getPdfAttachment()?.itemKey,
            it.attachments.map { a -> a.itemKey }, it.notes.map { n -> n.key })
    }

    private fun assertSame(query: LibraryQuery) {
        // Trash rows in memory never had their children attached; read from the database
        // they do (a trashed item's PDF opens from its row). Only that differs there.
        fun shown(items: List<Item>) = rows(items).map { if (query.place == LibraryPlace.Trash) it.take(3) else it }
        val expected = shown(memory.query(query).loadAll().map { it!! })
        val actual = shown(room.query(query).loadAll().map { it!! })
        // and a page from the middle, as the list reads it
        val rows = room.query(query)
        if (rows.size > 10) assertEquals("$query page", rows.keys.subList(5, 10), rows.load(5, 10).map { it!!.itemKey })
        assertEquals("$query", expected, actual)
    }

    private val places = listOf(
        LibraryPlace.Library, LibraryPlace.Unfiled, LibraryPlace.Trash, LibraryPlace.MyPublications,
        LibraryPlace.Collection("COLT0"), LibraryPlace.Collection("COLS10"), LibraryPlace.Collection("COLD21"),
    )

    @Test
    fun everyPlaceInEverySortOrder() {
        for (place in places) for (sort in SortMethod.entries) for (ascending in listOf(true, false)) {
            assertSame(LibraryQuery(place, sort = sort, ascending = ascending))
        }
        for (place in places.filterIsInstance<LibraryPlace.Collection>()) {
            assertSame(LibraryQuery(place, includeSubcollections = true))
        }
    }

    @Test
    fun filters() {
        val filters = listOf(
            LibraryFilter(hasPdf = true), LibraryFilter(hasNotes = true),
            LibraryFilter(hasPdf = true, hasNotes = true),
            LibraryFilter(hasHtml = true), LibraryFilter(hasOtherAttachment = true),
            LibraryFilter(hasPdf = true, hasHtml = true),
            LibraryFilter(tags = setOf("to-read")), LibraryFilter(tags = setOf("Method")),
            LibraryFilter(tags = setOf("method", "archive"), hasPdf = true),
            LibraryFilter(tags = setOf("théorie")), LibraryFilter(tags = setOf("absent")),
        )
        for (place in places) for (filter in filters) {
            assertSame(LibraryQuery(place, filter = filter, includeSubcollections = true))
        }
        val nonEmpty = room.query(LibraryQuery(LibraryPlace.Library, filter = LibraryFilter(hasPdf = true)))
        assertTrue("the fixture has items with PDFs", nonEmpty.size > 0)
        for (filter in listOf(LibraryFilter(hasHtml = true), LibraryFilter(hasOtherAttachment = true))) {
            assertTrue("the fixture has items for $filter", room.query(LibraryQuery(LibraryPlace.Library, filter = filter)).size > 0)
        }
    }

    @Test
    fun deviceStateFilters() {
        device = DeviceState(
            onDevice = attachmentKeys.filterIndexed { i, _ -> i % 3 == 0 }.toSet(),
            edited = attachmentKeys.filterIndexed { i, _ -> i % 7 == 0 }.toSet(),
            itemEdits = topKeys.filterIndexed { i, _ -> i % 11 == 0 }.toSet(),
        )
        for (place in places) for (filter in listOf(
            LibraryFilter(onDevice = true), LibraryFilter(editedHere = true),
            LibraryFilter(onDevice = true, hasPdf = true, tags = setOf("to-read")),
        )) {
            assertSame(LibraryQuery(place, filter = filter))
        }
    }

    private val searches = listOf(
        "governance", "sorlin", "SÖRLIN", "\"power memory\"", "19", "note", "about", "théorie", "méthod", "zzz",
        "first1", "to-read", "eq0001", "<p>", "  ", "\"\"", "governance archive",
        "état", "etat", "abstract about", "øresund", "oresund", "straße", "東京", "naive", "“", "—", "=", "≠",
    )

    // every field alone, the default set and all of them
    private val fieldSets = LibrarySearch.Field.entries.map { setOf(it) } +
            listOf(LibrarySearch.DEFAULT_FIELDS, LibrarySearch.Field.entries.toSet())

    private fun assertSearchesSame() {
        for (fieldSet in fieldSets) {
            fields = fieldSet
            for (search in searches) for (place in places) {
                assertSame(LibraryQuery(place, search = search, filter = LibraryFilter(hasPdf = search == "19")))
            }
        }
    }

    @Test
    fun searches() {
        assertTrue(!database.lacksSearchText(group))
        assertSearchesSame()
        fields = LibrarySearch.Field.entries.toSet()
        assertTrue("the fixture has search hits", room.query(LibraryQuery(LibraryPlace.Library, search = "governance")).size > 0)
    }

    @Test
    fun searchesWithoutStoredTextAndAfterFillingItIn() {
        val sql = database.db.openHelper.writableDatabase
        sql.execSQL("UPDATE ItemInfo SET searchTitle = NULL WHERE `group` = $group")
        assertTrue(database.lacksSearchText(group))
        assertSearchesSame()
        database.fillMissingKeys(group)
        assertTrue(!database.lacksSearchText(group))
        room.invalidate()
        assertSearchesSame()
    }

    @Test
    fun aTagAddedOnTheDeviceIsSearchedAtOnce() {
        fields = setOf(LibrarySearch.Field.TAGS)
        val key = topKeys.first { k -> database.loadItemsByKeys(listOf(k)).single().let { it.itemType != "note" && it.itemType != "attachment" } }
        val item = database.loadItemsByKeys(listOf(key)).single()
        val query = LibraryQuery(LibraryPlace.Library, search = "Zwölf-Ton")
        assertEquals(emptyList<String>(), room.query(query).keys)
        val edits = ItemEditStore(database)
        try {
            assertTrue(edits.record(group, item, ItemEdit(ItemEditKind.ADD_TAG, "Zwölf-Ton")))
            assertEquals(listOf(key), room.query(query).keys)
            assertEquals(listOf(key), room.query(query.copy(search = "zwolf")).keys)
            assertTrue(edits.record(group, item, ItemEdit(ItemEditKind.REMOVE_TAG, "Zwölf-Ton")))
            assertEquals(emptyList<String>(), room.query(query).keys)
        } finally {
            edits.remove(edits.pendingFor(group, key))
        }
    }

    @Test
    fun withoutStoredSortKeysAndAfterFillingThemIn() {
        val sql = database.db.openHelper.writableDatabase
        sql.execSQL("UPDATE ItemInfo SET sortTitle = NULL, sortAuthor = NULL, sortDate = NULL, sortDateAdded = NULL WHERE `group` = $group")
        assertTrue(database.lacksSortKeys(group))
        for (place in places) for (sort in SortMethod.entries) assertSame(LibraryQuery(place, sort = sort, ascending = sort != SortMethod.DATE))
        database.fillMissingKeys(group)
        assertTrue(!database.lacksSortKeys(group))
        for (place in places) for (sort in SortMethod.entries) assertSame(LibraryQuery(place, sort = sort, ascending = sort != SortMethod.DATE))
    }

    @Test
    fun tagCounts() {
        assertEquals(memory.tagCounts(), room.tagCounts())
        assertTrue(room.tagCounts().isNotEmpty())
    }
}
