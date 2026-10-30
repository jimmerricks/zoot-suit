package com.mickstarify.zooforzotero.ZoteroAPI

import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import java.util.concurrent.TimeUnit

/** The API key reaches Zotero, and not the storage host a file download is redirected to. */
class ZoteroAuthHeaderTest {
    private lateinit var zotero: MockWebServer
    private lateinit var storage: MockWebServer
    private lateinit var api: ZoteroAPI

    @Before
    fun setUp() {
        zotero = MockWebServer().apply { start() }
        storage = MockWebServer().apply { start() }
        api = ZoteroAPI("secret-key", "1", "user", mock<AttachmentStorageManager>(), mock<PreferenceManager>(),
            baseUrl = zotero.url("/").toString())
    }

    @After
    fun tearDown() {
        zotero.shutdown()
        storage.shutdown()
    }

    @Test
    fun `a file download sends the key to Zotero only`() {
        zotero.enqueue(MockResponse().setResponseCode(302).setHeader("Location", storage.url("/file.pdf")))
        storage.enqueue(MockResponse().setBody("%PDF"))

        // read within the stream: once it completes the streamed call is closed
        val body = api.service.getFileForUser("1", "ABCD2345").map { it.body()!!.string() }.blockingFirst()
        assertEquals("%PDF", body)

        val toZotero = zotero.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer secret-key", toZotero.getHeader("Authorization"))
        assertEquals("3", toZotero.getHeader("Zotero-API-Version"))
        val toStorage = storage.takeRequest(5, TimeUnit.SECONDS)!!
        assertNull(toStorage.getHeader("Authorization"))
        assertNull(toStorage.getHeader("Zotero-API-Key"))
    }
}

class RedactKeysTest {
    @Test
    fun `a key in a keys URL is hidden`() {
        assertEquals("--> GET https://api.zotero.org/keys/…", redactKeys("--> GET https://api.zotero.org/keys/AbC123xyz"))
        assertEquals("<-- 200 https://api.zotero.org/keys/… (5ms)", redactKeys("<-- 200 https://api.zotero.org/keys/AbC123xyz (5ms)"))
        assertEquals("--> GET https://api.zotero.org/users/1/items", redactKeys("--> GET https://api.zotero.org/users/1/items"))
    }
}
