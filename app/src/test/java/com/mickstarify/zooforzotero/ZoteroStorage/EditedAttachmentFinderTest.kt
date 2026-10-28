package com.mickstarify.zooforzotero.ZoteroStorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditedAttachmentFinderTest {
    private data class FakeFile(var size: Long, var mtime: Long, var md5: String)

    private val files = HashMap<String, FakeFile>()
    private var hashes = 0
    private val finder = EditedAttachmentFinder(
        stampOf = { key -> files[key]?.let { Pair(it.size, it.mtime) } },
        md5Of = { key -> hashes++; files.getValue(key).md5 }
    )

    @Test
    fun `a file that differs from its base is edited, one that matches is not`() {
        files["AAAA"] = FakeFile(10, 1, "edited")
        files["BBBB"] = FakeFile(10, 1, "base-b")
        assertEquals(setOf("AAAA"), finder.edited(mapOf("AAAA" to "base-a", "BBBB" to "base-b")))
    }

    @Test
    fun `comparison ignores case`() {
        files["AAAA"] = FakeFile(10, 1, "ABCDEF")
        assertEquals(emptySet<String>(), finder.edited(mapOf("AAAA" to "abcdef")))
    }

    @Test
    fun `a file missing from the device is not edited`() {
        assertEquals(emptySet<String>(), finder.edited(mapOf("AAAA" to "base")))
    }

    @Test
    fun `an unchanged file is hashed once`() {
        files["AAAA"] = FakeFile(10, 1, "md5")
        finder.edited(mapOf("AAAA" to "md5"))
        finder.edited(mapOf("AAAA" to "md5"))
        assertEquals(1, hashes)
    }

    @Test
    fun `a new size or mtime means hashing again`() {
        files["AAAA"] = FakeFile(10, 1, "md5")
        finder.edited(mapOf("AAAA" to "md5"))
        files["AAAA"] = FakeFile(10, 2, "saved-by-viewer")
        assertEquals(setOf("AAAA"), finder.edited(mapOf("AAAA" to "md5")))
        assertEquals(2, hashes)
    }

    @Test
    fun `an unreadable file counts as not there`() {
        val failing = EditedAttachmentFinder({ Pair(1L, 1L) }, { throw java.io.IOException() })
        assertNull(failing.currentMd5("AAAA"))
        assertEquals(emptySet<String>(), failing.edited(mapOf("AAAA" to "base")))
    }

    private class MapStore : EditedAttachmentFinder.Store {
        val map = HashMap<String, EditedAttachmentFinder.Cached>()
        override fun get(itemKey: String) = map[itemKey]
        override fun put(itemKey: String, cached: EditedAttachmentFinder.Cached) { map[itemKey] = cached }
        override fun remove(itemKey: String) { map.remove(itemKey) }
    }

    private fun finderWith(store: EditedAttachmentFinder.Store) = EditedAttachmentFinder(
        stampOf = { key -> files[key]?.let { Pair(it.size, it.mtime) } },
        md5Of = { key -> hashes++; files.getValue(key).md5 },
        store = store,
    )

    @Test
    fun `a hash kept in the store isn't computed again by a new finder`() {
        val store = MapStore()
        files["AAAA"] = FakeFile(10, 1, "md5")
        finderWith(store).edited(mapOf("AAAA" to "md5"))
        // a new process
        assertEquals(setOf("AAAA"), finderWith(store).edited(mapOf("AAAA" to "other")))
        assertEquals(1, hashes)
    }

    @Test
    fun `a stored hash for an older size or mtime is replaced`() {
        val store = MapStore()
        files["AAAA"] = FakeFile(10, 1, "md5")
        finderWith(store).edited(mapOf("AAAA" to "md5"))
        files["AAAA"] = FakeFile(10, 2, "saved-by-viewer")
        assertEquals(setOf("AAAA"), finderWith(store).edited(mapOf("AAAA" to "md5")))
        assertEquals(2, hashes)
        assertEquals("saved-by-viewer", store.map["AAAA"]?.md5)
    }

    @Test
    fun `a file that's gone is forgotten by the store too`() {
        val store = MapStore()
        files["AAAA"] = FakeFile(10, 1, "md5")
        finderWith(store).edited(mapOf("AAAA" to "md5"))
        files.remove("AAAA")
        finderWith(store).edited(mapOf("AAAA" to "md5"))
        assertNull(store.map["AAAA"])
    }

    @Test
    fun `without a known mtime a hash isn't stored`() {
        val store = MapStore()
        files["AAAA"] = FakeFile(10, 0, "md5")
        finderWith(store).edited(mapOf("AAAA" to "md5"))
        assertNull(store.map["AAAA"])
    }

    @Test
    fun `a failing store doesn't stop the check`() {
        val broken = object : EditedAttachmentFinder.Store {
            override fun get(itemKey: String): EditedAttachmentFinder.Cached? = throw IllegalStateException()
            override fun put(itemKey: String, cached: EditedAttachmentFinder.Cached) = throw IllegalStateException()
            override fun remove(itemKey: String) = throw IllegalStateException()
        }
        files["AAAA"] = FakeFile(10, 1, "edited")
        assertEquals(setOf("AAAA"), finderWith(broken).edited(mapOf("AAAA" to "base")))
    }
}
