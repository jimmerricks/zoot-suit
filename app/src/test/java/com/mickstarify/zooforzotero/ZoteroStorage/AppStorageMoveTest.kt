package com.mickstarify.zooforzotero.ZoteroStorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppStorageMoveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val old by lazy { tmp.newFolder("old") }
    private val new by lazy { File(tmp.root, "new") }

    private fun file(dir: File, key: String, name: String, text: String, mtime: Long = 1_600_000_000_000): File =
        File(File(dir, key).apply { mkdirs() }, name).apply {
            writeText(text)
            setLastModified(mtime)
        }

    /** A move across filesystems: the folder rename fails, as from shared storage. */
    private fun copyingMove(space: Long = Long.MAX_VALUE / 2, reserve: Long = 0) =
        AppStorageMove(old, new, reserve, usableSpace = { space }, renameFolder = { _, _ -> false })

    @Test
    fun `copied folders keep their files and modification times, and leave the old place`() {
        file(old, "AAAA1111", "Paper.pdf", "pdf bytes", mtime = 1_500_000_000_000)
        file(old, "BBBB2222", "Page.html", "html")

        assertTrue(copyingMove().run())

        val moved = File(new, "AAAA1111/Paper.pdf")
        assertEquals("pdf bytes", moved.readText())
        assertEquals(1_500_000_000_000, moved.lastModified())
        assertEquals("html", File(new, "BBBB2222/Page.html").readText())
        assertEquals(emptyList<String>(), old.list()!!.toList())
        assertEquals(setOf("AAAA1111", "BBBB2222"), new.list()!!.toSet())
    }

    @Test
    fun `a folder already in the new place wins over the old copy`() {
        file(old, "AAAA1111", "Paper.pdf", "old")
        file(new, "AAAA1111", "Paper.pdf", "downloaded again")

        assertTrue(copyingMove().run())

        assertEquals("downloaded again", File(new, "AAAA1111/Paper.pdf").readText())
        assertFalse(File(old, "AAAA1111").exists())
    }

    @Test
    fun `without the space it stops and leaves the old files where they are`() {
        file(old, "AAAA1111", "Paper.pdf", "x".repeat(1000))

        assertFalse(copyingMove(space = 1500, reserve = 1000).run())

        assertTrue(File(old, "AAAA1111/Paper.pdf").exists())
        assertFalse(File(new, "AAAA1111").exists())
    }

    @Test
    fun `a half copy from a stopped move is cleared and the folder copied again`() {
        file(old, "AAAA1111", "Paper.pdf", "complete")
        file(new, "AAAA1111.moving", "Paper.pdf", "comp")

        assertTrue(copyingMove().run())

        assertEquals("complete", File(new, "AAAA1111/Paper.pdf").readText())
        assertFalse(File(new, "AAAA1111.moving").exists())
    }

    @Test
    fun `on one filesystem a folder is renamed`() {
        file(old, "AAAA1111", "Paper.pdf", "pdf")

        assertTrue(AppStorageMove(old, new).run())

        assertEquals("pdf", File(new, "AAAA1111/Paper.pdf").readText())
        assertFalse(File(old, "AAAA1111").exists())
    }

    @Test
    fun `nothing to move is done`() {
        assertTrue(AppStorageMove(null, new).run())
        assertTrue(AppStorageMove(old, new).run())
        assertFalse(new.exists())
    }

    @Test
    fun `only attachment folders move`() {
        file(old, "AAAA1111", "Paper.pdf", "pdf")
        file(old, "image_cache", "x.png", "png")

        assertTrue(copyingMove().run())

        assertTrue(File(new, "AAAA1111/Paper.pdf").exists())
        assertTrue(File(old, "image_cache/x.png").exists())
        assertFalse(File(new, "image_cache").exists())
    }
}
