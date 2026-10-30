package com.mickstarify.zooforzotero.ZoteroStorage

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeFileNameTest {
    @Test
    fun `an ordinary name stays`() {
        assertEquals("Smith 2020 - Title.pdf", safeFileName("Smith 2020 - Title.pdf"))
        assertEquals("a..b.pdf", safeFileName("a..b.pdf"))
    }

    @Test
    fun `a path keeps only its last part`() {
        assertEquals("probe.pdf", safeFileName("../../../../probe.pdf"))
        assertEquals("x.xml", safeFileName("/data/data/app/shared_prefs/x.xml"))
    }

    @Test
    fun `no usable name falls back`() {
        assertEquals("unknown.pdf", safeFileName(null))
        assertEquals("unknown.pdf", safeFileName(""))
        assertEquals("unknown.pdf", safeFileName(".."))
        assertEquals("unknown.pdf", safeFileName("dir/"))
    }
}
