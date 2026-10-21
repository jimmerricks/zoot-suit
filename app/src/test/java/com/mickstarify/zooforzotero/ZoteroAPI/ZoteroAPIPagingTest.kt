package com.mickstarify.zooforzotero.ZoteroAPI

import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import java.util.concurrent.TimeUnit

/** Paging through items and collections when the server returns fewer than it announced. */
class ZoteroAPIPagingTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ZoteroAPI

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = ZoteroAPI("key", "1", "user", mock<AttachmentStorageManager>(), mock<PreferenceManager>(),
            baseUrl = server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun items(vararg keys: String) = keys.joinToString(",", "[", "]") {
        """{"key": "$it", "version": 1, "data": {"key": "$it", "version": 1, "itemType": "book", "title": "$it"}}"""
    }

    private fun collections(vararg keys: String) = keys.joinToString(",", "[", "]") {
        """{"key": "$it", "version": 1, "data": {"name": "$it", "parentCollection": false}}"""
    }

    private fun page(body: String, total: Int, version: Int) = server.enqueue(
        MockResponse().setBody(body)
            .setHeader("Total-Results", total).setHeader("Last-Modified-Version", version)
    )

    @Test
    fun itemsStopAtAnEmptyPage() {
        // Total-Results says 3, but one item was deleted meanwhile without a version change
        page(items("AAAA0001", "AAAA0002"), total = 3, version = 10)
        page(items(), total = 2, version = 10)

        val pages = api.getItems().test().awaitDone(5, TimeUnit.SECONDS)

        pages.assertComplete()
        assertEquals(listOf(2), pages.values().map { it.items.size })
        assertEquals(2, server.requestCount) // didn't ask for the empty page again
    }

    @Test
    fun itemsRestartWhenTheLibraryChangedWhilePaging() {
        page(items("AAAA0001", "AAAA0002"), total = 3, version = 10)
        page(items(), total = 2, version = 11)

        val pages = api.getItems().test().awaitDone(5, TimeUnit.SECONDS)

        pages.assertError(LibraryVersionMisMatchException::class.java)
    }

    @Test
    fun itemsPageByWhatTheServerReturned() {
        page(items("AAAA0001", "AAAA0002"), total = 3, version = 10)
        page(items("AAAA0003"), total = 3, version = 10)

        val pages = api.getItems().test().awaitDone(5, TimeUnit.SECONDS)

        pages.assertComplete()
        assertEquals(listOf(2, 1), pages.values().map { it.items.size })
        assertTrue(server.takeRequest().path!!.contains("start=0"))
        assertTrue(server.takeRequest().path!!.contains("start=2"))
    }

    @Test
    fun collectionsStopAtAnEmptyPage() {
        page(collections("CCCC0001"), total = 2, version = 7)
        page(collections(), total = 1, version = 7)

        val pages = api.getCollections(libraryVersion = 0).test().awaitDone(5, TimeUnit.SECONDS)

        pages.assertComplete()
        assertEquals(listOf(1), pages.values().map { it.collections.size })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun collectionsReportTheFirstPagesVersion() {
        // the version SyncManager stores as CollectionsLibraryVersion once all pages are in
        page(collections("CCCC0001"), total = 2, version = 7)
        page(collections("CCCC0002"), total = 2, version = 8)

        val pages = api.getCollections(libraryVersion = 0).test().awaitDone(5, TimeUnit.SECONDS)

        pages.assertComplete()
        assertEquals(7, pages.values().first().lastModifiedVersion)
    }
}
