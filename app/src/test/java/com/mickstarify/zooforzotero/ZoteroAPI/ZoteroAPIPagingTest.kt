package com.mickstarify.zooforzotero.ZoteroAPI

import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.ItemsDownloadProgress
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    /** Serves a [total]-item library in pages of up to 100; later pages answer sooner. */
    private inner class LibraryDispatcher(val total: Int, val version: Int = 10) : Dispatcher() {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val start = request.requestUrl!!.queryParameter("start")!!.toInt()
            val limit = request.requestUrl!!.queryParameter("limit")!!.toInt()
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try {
                Thread.sleep(maxOf(0L, 200L - start / 10))
            } finally {
                inFlight.decrementAndGet()
            }
            val keys = (start until minOf(start + limit, total)).map { "K%07d".format(it) }
            return MockResponse().setBody(items(*keys.toTypedArray()))
                .setHeader("Total-Results", total).setHeader("Last-Modified-Version", version)
        }
    }

    @Test
    fun itemsArriveInOrderWhileFetchedInParallel() {
        val dispatcher = LibraryDispatcher(total = 1050)
        server.dispatcher = dispatcher

        val pages = api.getItems().test().awaitDone(10, TimeUnit.SECONDS)

        pages.assertComplete()
        val keys = pages.values().flatMap { page -> page.items.map { it.ItemKey } }
        assertEquals((0 until 1050).map { "K%07d".format(it) }, keys)
        assertEquals(11, server.requestCount)
        assertTrue("pages overlapped", dispatcher.maxInFlight.get() > 1)
        assertTrue("at most 4 at once", dispatcher.maxInFlight.get() <= 4)
    }

    @Test
    fun itemsResumeFromTheSavedOffset() {
        server.dispatcher = LibraryDispatcher(total = 450)

        val pages = api.getItems(downloadProgress = ItemsDownloadProgress(10, 200, 450))
            .test().awaitDone(10, TimeUnit.SECONDS)

        pages.assertComplete()
        assertEquals("K0000200", pages.values().first().items.first().ItemKey)
        assertEquals(250, pages.values().sumOf { it.items.size })
        assertEquals(3, server.requestCount) // 200, 300, 400
    }

    @Test
    fun itemsAreRequestedGzippedAndDecoded() {
        // OkHttp asks for gzip and unpacks it by itself; nothing in the app sets it up
        val gzipped = Buffer().also { buf ->
            GzipSink(buf).buffer().use { it.writeUtf8(items("AAAA0001")) }
        }
        server.enqueue(
            MockResponse().setBody(gzipped).setHeader("Content-Encoding", "gzip")
                .setHeader("Total-Results", 1).setHeader("Last-Modified-Version", 10)
        )

        val pages = api.getItems().test().awaitDone(5, TimeUnit.SECONDS)

        pages.assertComplete()
        assertEquals("AAAA0001", pages.values().single().items.single().ItemKey)
        val request = server.takeRequest()
        assertEquals("gzip", request.getHeader("Accept-Encoding"))
        assertFalse(request.path!!.contains("format="))
    }

    @Test
    fun topLevelAndChildPassesAskForTheirOwnListings() {
        page(items("AAAA0001"), total = 1, version = 10)
        page(items("AAAA0002"), total = 1, version = 10)

        api.getItems(scope = ItemScope.TOP).test().awaitDone(5, TimeUnit.SECONDS).assertComplete()
        api.getItems(scope = ItemScope.CHILDREN).test().awaitDone(5, TimeUnit.SECONDS).assertComplete()

        val top = server.takeRequest().requestUrl!!
        assertEquals("/users/1/items/top", top.encodedPath)
        assertEquals(null, top.queryParameter("itemType"))
        val children = server.takeRequest().requestUrl!!
        assertEquals("/users/1/items", children.encodedPath)
        assertEquals("attachment || note", children.queryParameter("itemType"))
    }

    @Test
    fun resumedProgressKeepsItsListing() {
        page(items("AAAA0003"), total = 3, version = 10)

        api.getItems(downloadProgress = ItemsDownloadProgress(10, 2, 3, ItemScope.CHILDREN))
            .test().awaitDone(5, TimeUnit.SECONDS).assertComplete()

        val url = server.takeRequest().requestUrl!!
        assertEquals("attachment || note", url.queryParameter("itemType"))
        assertEquals("2", url.queryParameter("start"))
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
