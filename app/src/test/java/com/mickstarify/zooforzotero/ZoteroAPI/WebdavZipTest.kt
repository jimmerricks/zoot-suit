package com.mickstarify.zooforzotero.ZoteroAPI

import com.mickstarify.zooforzotero.ZoteroStorage.md5Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class WebdavZipTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bytes = ByteArray(200_000) { (it % 251).toByte() }

    @Test
    fun `a zipped file holds one entry under its name and unzips to the same bytes`() {
        val zip = tmp.newFile("KEY.zip")
        zipOneFile("Paper 1.pdf", bytes.inputStream(), zip)

        ZipFile(zip).use { archive ->
            assertEquals(listOf("Paper 1.pdf"), archive.entries().toList().map { it.name })
        }
        val out = tmp.newFile("out")
        unzipFirstFile(zip, out)
        assertArrayEquals(bytes, out.readBytes())
    }

    @Test
    fun `zipping returns the md5 of the bytes zipped`() {
        val zip = tmp.newFile("KEY.zip")
        val md5 = zipOneFile("a.pdf", bytes.inputStream(), zip)
        assertEquals(md5Hex(bytes.inputStream()), md5)
        assertEquals(32, md5.length)
    }

    @Test
    fun `the entry's name never decides where the file goes`() {
        val zip = tmp.newFile("evil.zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("../../outside.pdf"))
            it.write(bytes)
            it.closeEntry()
        }
        val out = tmp.newFile("out")
        unzipFirstFile(zip, out)
        assertArrayEquals(bytes, out.readBytes())
        assertEquals(setOf("evil.zip", "out"), tmp.root.list()!!.toSet())
    }

    @Test
    fun `a folder entry is skipped`() {
        val zip = tmp.newFile("dir.zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("folder/"))
            it.closeEntry()
            it.putNextEntry(ZipEntry("folder/a.pdf"))
            it.write(bytes)
            it.closeEntry()
        }
        val out = tmp.newFile("out")
        unzipFirstFile(zip, out)
        assertArrayEquals(bytes, out.readBytes())
    }

    @Test
    fun `unzipping stops past the limit and says why`() {
        val zip = tmp.newFile("big.zip")
        zipOneFile("a.pdf", bytes.inputStream(), zip)
        val out = tmp.newFile("out")
        try {
            unzipFirstFile(zip, out, limit = bytes.size - 1L)
            fail("unzipped past the limit")
        } catch (e: NotEnoughSpaceException) {
            assertTrue(out.length() < bytes.size)
        }
        unzipFirstFile(zip, out, limit = bytes.size.toLong())
        assertArrayEquals(bytes, out.readBytes())
    }

    @Test(expected = IOException::class)
    fun `an empty zip is an error`() {
        val zip = tmp.newFile("empty.zip")
        ZipOutputStream(zip.outputStream()).use { }
        unzipFirstFile(zip, tmp.newFile("out"))
    }
}
