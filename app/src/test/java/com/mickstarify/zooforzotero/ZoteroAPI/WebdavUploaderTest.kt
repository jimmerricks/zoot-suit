package com.mickstarify.zooforzotero.ZoteroAPI

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class WebdavUploaderTest {
    private lateinit var server: MockWebServer
    private lateinit var uploader: WebdavUploader
    private lateinit var zip: File

    private val oldProp = """<properties version="1"><mtime>1</mtime><hash>aaaa</hash></properties>"""
    private val newProp = """<properties version="1"><mtime>2</mtime><hash>bbbb</hash></properties>"""
    private val expected = RemoteProp("aaaa", "\"etag-1\"", oldProp)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        uploader = WebdavUploader(OkHttpClient(), server.url("/dav/zotero").toString())
        zip = File.createTempFile("ABCD2345_NEW", ".zip").apply { writeText("zipdata") }
    }

    @After
    fun tearDown() {
        server.shutdown()
        zip.delete()
    }

    private fun respond(vararg codes: Int) = codes.forEach { server.enqueue(MockResponse().setResponseCode(it)) }

    private fun requests(): List<RecordedRequest> =
        generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()

    private fun RecordedRequest.line() = "$method ${requestUrl!!.encodedPath}"

    @Test
    fun `replaces in order stage, claim, swap, commit with the right conditions`() {
        respond(404, 201, 204, 204, 201) // delete leftover, stage, claim, move, commit
        uploader.replace("abcd2345", zip, newProp, expected)

        val r = requests()
        assertEquals(
            listOf(
                "DELETE /dav/zotero/ABCD2345_NEW.zip",
                "PUT /dav/zotero/ABCD2345_NEW.zip",
                "DELETE /dav/zotero/ABCD2345.prop",
                "MOVE /dav/zotero/ABCD2345_NEW.zip",
                "PUT /dav/zotero/ABCD2345.prop"
            ),
            r.map { it.line() }
        )
        assertEquals("zipdata", r[1].body.readUtf8())
        assertEquals("\"etag-1\"", r[2].getHeader("If-Match"))
        assertTrue(r[3].getHeader("Destination")!!.endsWith("/dav/zotero/ABCD2345.zip"))
        assertEquals("T", r[3].getHeader("Overwrite"))
        assertEquals("*", r[4].getHeader("If-None-Match"))
        assertEquals(newProp, r[4].body.readUtf8())
    }

    @Test
    fun `a changed prop at claim time stops before anything visible changes`() {
        respond(404, 201, 412, 204) // ..., claim fails, cleanup of staged file
        try {
            uploader.replace("ABCD2345", zip, newProp, expected)
            fail("expected a conflict")
        } catch (e: RemoteAttachmentChangedException) {
        }
        val lines = requests().map { it.line() }
        assertEquals("DELETE /dav/zotero/ABCD2345_NEW.zip", lines.last())
        assertTrue(lines.none { it.startsWith("MOVE") })
        assertTrue(lines.none { it == "PUT /dav/zotero/ABCD2345.prop" })
    }

    @Test
    fun `a prop already removed by someone else is a conflict too`() {
        respond(404, 201, 404, 204)
        try {
            uploader.replace("ABCD2345", zip, newProp, expected)
            fail("expected a conflict")
        } catch (e: RemoteAttachmentChangedException) {
        }
        assertTrue(requests().none { it.method == "MOVE" })
    }

    @Test
    fun `first upload with no prop skips the claim but still commits conditionally`() {
        respond(404, 201, 201, 201) // delete leftover, stage, move, commit
        uploader.replace("ABCD2345", zip, newProp, null)
        val r = requests()
        assertEquals(listOf("DELETE", "PUT", "MOVE", "PUT"), r.map { it.method })
        assertEquals("*", r[3].getHeader("If-None-Match"))
    }

    @Test
    fun `a failed move restores the old prop and leaves the zip alone`() {
        respond(404, 201, 204, 502, 201, 204) // ..., move fails, restore prop, drop staged
        try {
            uploader.replace("ABCD2345", zip, newProp, expected)
            fail("expected an IOException")
        } catch (e: IOException) {
        }
        val r = requests()
        val restore = r[4]
        assertEquals("PUT /dav/zotero/ABCD2345.prop", restore.line())
        assertEquals(oldProp, restore.body.readUtf8())
        assertEquals("*", restore.getHeader("If-None-Match"))
        assertEquals("DELETE /dav/zotero/ABCD2345_NEW.zip", r[5].line())
    }

    @Test
    fun `a prop re-created by another client at commit time is a conflict`() {
        respond(404, 201, 204, 204, 412)
        try {
            uploader.replace("ABCD2345", zip, newProp, expected)
            fail("expected a conflict")
        } catch (e: RemoteAttachmentChangedException) {
        }
    }

    @Test
    fun `without an ETag the prop is re-read and compared before deleting`() {
        val noEtag = expected.copy(etag = null)
        respond(404, 201)
        server.enqueue(MockResponse().setResponseCode(200).setBody(oldProp)) // re-read: unchanged
        respond(204, 204, 201)
        uploader.replace("ABCD2345", zip, newProp, noEtag)
        val r = requests()
        assertEquals("GET /dav/zotero/ABCD2345.prop", r[2].line())
        assertNull(r[3].getHeader("If-Match"))
        assertEquals("DELETE /dav/zotero/ABCD2345.prop", r[3].line())
    }

    @Test
    fun `readProp returns hash and ETag, or null when absent`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(oldProp).setHeader("ETag", "\"e\""))
        respond(404)
        val prop = uploader.readProp("abcd2345")!!
        assertEquals("aaaa", prop.hash)
        assertEquals("\"e\"", prop.etag)
        assertEquals(oldProp, prop.raw)
        assertNull(uploader.readProp("abcd2345"))
        assertEquals("GET /dav/zotero/ABCD2345.prop", requests().first().line())
    }
}
