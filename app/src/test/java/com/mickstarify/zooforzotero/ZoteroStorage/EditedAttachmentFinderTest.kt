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
}
