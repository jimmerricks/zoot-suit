package com.mickstarify.zooforzotero.ZoteroStorage

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.mickstarify.zooforzotero.ZoteroAPI.WriteResponse
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroAPI
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEdit
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditKind.ADD_TAG
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditKind.ADD_TO_COLLECTION
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditStore
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditUploader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import java.net.UnknownHostException

/**
 * The uploader against a fake Zotero (items by key, with versions) and a store that keeps
 * the pending edits in memory.
 */
class ItemEditUploaderTest {
    private fun data(json: String): JsonObject = Gson().fromJson(json, JsonObject::class.java)
    private fun array(json: String): JsonElement = Gson().fromJson(json, JsonElement::class.java)

    /** Pending edits by item key; what the store would hold. */
    private val pending = LinkedHashMap<String, MutableList<ItemEdit>>()
    private var nextId = 1L

    private fun edit(key: String, edit: ItemEdit) {
        pending.getOrPut(key) { ArrayList() } += edit.copy(id = nextId++)
    }

    private val store: ItemEditStore = mock {
        on { pending(any()) } doAnswer { pending.mapValues { it.value.toList() } }
        on { beginUpload(any(), any()) } doAnswer { pending[it.getArgument<String>(1)]?.toList() ?: emptyList() }
        on { remove(any()) } doAnswer { inv ->
            val ids = inv.getArgument<List<ItemEdit>>(0).map { it.id }.toSet()
            pending.values.forEach { list -> list.removeAll { it.id in ids } }
            pending.values.removeAll { it.isEmpty() }
            null
        }
    }

    /** Zotero's items: key -> (version, data). */
    private val server = HashMap<String, Pair<Int, JsonObject>>()
    private val reads = ArrayList<List<String>>()
    private val writes = ArrayList<List<JsonObject>>()

    /** Per write, by index: a failure to answer instead of writing. */
    private var failWith: (Int, JsonObject) -> WriteResponse.Failure? = { _, _ -> null }

    private val api: ZoteroAPI = mock {
        on { getItemsData(any()) } doAnswer { inv ->
            val keys = inv.getArgument<List<String>>(0)
            reads += keys
            keys.mapNotNull { k -> server[k]?.let { k to it } }.toMap()
        }
        on { updateItems(any()) } doAnswer { inv ->
            val changes = inv.getArgument<List<JsonObject>>(0)
            writes += changes
            changes.mapIndexed { index, change ->
                val key = change.get("key").asString
                failWith(index, change) ?: run {
                    val (version, current) = server.getValue(key)
                    if (change.get("version").asInt != version) return@run WriteResponse.Failure(412, "modified since")
                    val updated = current.deepCopy()
                    change.entrySet().filter { it.key != "key" && it.key != "version" }.forEach { updated.add(it.key, it.value) }
                    server[key] = (version + 1) to updated
                    null
                }
            }
        }
    }

    private fun item(key: String, json: String = """{"tags":[],"collections":[]}""", version: Int = 5) {
        server[key] = version to data(json)
    }

    private fun upload() = ItemEditUploader(api, store).uploadAll()

    @Test
    fun `edits of many items go up 50 to a request`() {
        repeat(120) { i ->
            val key = "ITEM%04d".format(i)
            item(key)
            edit(key, ItemEdit(ADD_TAG, "t"))
        }
        val result = upload()
        assertEquals(120, result.uploaded)
        assertEquals(listOf(50, 50, 20), reads.map { it.size })
        assertEquals(3, writes.size)
        assertTrue(pending.isEmpty())
        assertEquals(array("""[{"tag":"t"}]"""), server.getValue("ITEM0007").second.get("tags"))
    }

    @Test
    fun `an item deleted in Zotero loses its edits, the others go up`() {
        item("AAAA2222")
        edit("AAAA2222", ItemEdit(ADD_TAG, "t"))
        edit("GONE2222", ItemEdit(ADD_TAG, "t"))
        val result = upload()
        assertEquals(1, result.uploaded)
        assertEquals(1, result.gone)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `an item changed in Zotero between read and write is read and written again`() {
        item("AAAA2222", """{"tags":[{"tag":"old"}],"collections":[]}""")
        edit("AAAA2222", ItemEdit(ADD_TAG, "new"))
        var first = true
        failWith = { _, _ ->
            if (first) {
                first = false
                // someone else adds a tag meanwhile
                server["AAAA2222"] = 6 to data("""{"tags":[{"tag":"old"},{"tag":"theirs"}],"collections":[]}""")
                WriteResponse.Failure(412, "modified since")
            } else null
        }
        val result = upload()
        assertEquals(1, result.uploaded)
        assertEquals(2, reads.size)
        assertEquals(
            array("""[{"tag":"old"},{"tag":"theirs"},{"tag":"new"}]"""),
            server.getValue("AAAA2222").second.get("tags"),
        )
    }

    @Test
    fun `an addition to a collection deleted in Zotero is dropped, the item's other edits go up`() {
        item("AAAA2222")
        edit("AAAA2222", ItemEdit(ADD_TO_COLLECTION, "GONEC222"))
        edit("AAAA2222", ItemEdit(ADD_TAG, "t"))
        failWith = { _, change ->
            if (change.getAsJsonArray("collections")?.any { it.asString == "GONEC222" } == true) {
                WriteResponse.Failure(409, "Collection 1/GONEC222 doesn't exist", missingCollection = "GONEC222")
            } else null
        }
        val result = upload()
        assertEquals(1, result.missingCollections)
        assertEquals(1, result.uploaded)
        assertTrue(result.failures.isEmpty())
        assertTrue(pending.isEmpty())
        assertEquals(array("""[{"tag":"t"}]"""), server.getValue("AAAA2222").second.get("tags"))
    }

    @Test
    fun `another refusal keeps the item's edits for the next sync`() {
        item("AAAA2222")
        edit("AAAA2222", ItemEdit(ADD_TAG, "t"))
        failWith = { _, _ -> WriteResponse.Failure(400, "Invalid property") }
        val result = upload()
        assertEquals(1, result.failures.size)
        assertEquals(1, pending.getValue("AAAA2222").size)
    }

    @Test
    fun `offline stops at the first request and keeps everything`() {
        repeat(60) { i -> edit("ITEM%04d".format(i), ItemEdit(ADD_TAG, "t")) }
        val offlineApi: ZoteroAPI = mock {
            on { getItemsData(any()) } doAnswer { throw RuntimeException(UnknownHostException("api.zotero.org")) }
        }
        val result = ItemEditUploader(offlineApi, store).uploadAll()
        assertTrue(result.offline)
        assertEquals(60, pending.size)
    }

    @Test
    fun `edits Zotero already has are forgotten without a write`() {
        item("AAAA2222", """{"tags":[{"tag":"t"}],"collections":[]}""")
        edit("AAAA2222", ItemEdit(ADD_TAG, "t"))
        val result = upload()
        assertEquals(1, result.uploaded)
        assertTrue(writes.isEmpty())
        assertTrue(pending.isEmpty())
    }
}
