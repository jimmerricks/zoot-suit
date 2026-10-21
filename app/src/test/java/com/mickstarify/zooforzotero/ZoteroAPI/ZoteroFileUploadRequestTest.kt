package com.mickstarify.zooforzotero.ZoteroAPI

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory

/**
 * The Zotero File Storage requests as https://www.zotero.org/support/dev/web_api/v3/file_upload
 * describes them: form-encoded bodies, and exactly one precondition header.
 */
class ZoteroFileUploadRequestTest {
    private lateinit var server: MockWebServer
    private lateinit var service: ZoteroAPIService

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        service = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
            .build().create(ZoteroAPIService::class.java)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"exists\":1}"))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `authorisation for a first upload is a form with If-None-Match`() {
        service.uploadAttachmentAuthorization(
            "123", "ABCD2345", "0123abcd", "a b.pdf", 42L, 1000L,
            ifMatch = null, ifNoneMatch = "*"
        ).blockingFirst()

        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/users/123/items/ABCD2345/file?params=1", r.path)
        assertTrue(r.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals("md5=0123abcd&filename=a%20b.pdf&filesize=42&mtime=1000", r.body.readUtf8())
        assertEquals("*", r.getHeader("If-None-Match"))
        assertNull(r.getHeader("If-Match"))
    }

    @Test
    fun `replacing a file sends If-Match with the old md5 only`() {
        service.uploadAttachmentAuthorization(
            "123", "ABCD2345", "new", "x.pdf", 1L, 1L,
            ifMatch = "old", ifNoneMatch = null
        ).blockingFirst()

        val r = server.takeRequest()
        assertEquals("old", r.getHeader("If-Match"))
        assertNull(r.getHeader("If-None-Match"))
    }

    @Test
    fun `registering the upload is a form with the upload key`() {
        service.registerUpload("123", "ABCD2345", "key1", ifMatch = "old", ifNoneMatch = null)
            .blockingFirst()

        val r = server.takeRequest()
        assertEquals("/users/123/items/ABCD2345/file", r.path)
        assertTrue(r.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals("upload=key1", r.body.readUtf8())
        assertEquals("old", r.getHeader("If-Match"))
    }
}
