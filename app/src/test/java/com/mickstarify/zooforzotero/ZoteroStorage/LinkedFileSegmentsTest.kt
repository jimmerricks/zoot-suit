package com.mickstarify.zooforzotero.ZoteroStorage

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkedFileSegmentsTest {
    @Test
    fun `paths from each system split into names`() {
        assertEquals(listOf("C:", "Users", "me", "a.pdf"), linkedFileSegments("C:\\Users\\me\\a.pdf"))
        assertEquals(listOf("home", "me", "a.pdf"), linkedFileSegments("/home/me/a.pdf"))
        assertEquals(listOf("Sub", "a.pdf"), linkedFileSegments("attachments:Sub/a.pdf"))
    }

    @Test
    fun `parts that lead out of the folder are dropped`() {
        assertEquals(listOf("shared_prefs", "x.xml"), linkedFileSegments("../../shared_prefs/x.xml"))
        assertEquals(listOf("a", "b.pdf"), linkedFileSegments("a/./..//b.pdf"))
        assertEquals(listOf("a..b.pdf"), linkedFileSegments("a..b.pdf"))
    }

    @Test
    fun `no path, no names`() {
        assertEquals(emptyList<String>(), linkedFileSegments(null))
        assertEquals(emptyList<String>(), linkedFileSegments(""))
        assertEquals(emptyList<String>(), linkedFileSegments("../.."))
    }
}
