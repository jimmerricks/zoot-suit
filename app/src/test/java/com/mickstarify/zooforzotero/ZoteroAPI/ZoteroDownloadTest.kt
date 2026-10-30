package com.mickstarify.zooforzotero.ZoteroAPI

import android.content.Context
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemData
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemInfo
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.io.File
import java.util.concurrent.TimeUnit

/** A Zotero file download reaches the attachment's copy only once it's complete. */
class ZoteroDownloadTest {
    private lateinit var server: MockWebServer
    private val temps = mutableListOf<File>()
    private var written: String? = null

    private val storage = mock<AttachmentStorageManager> {
        on { downloadTempFile(any()) } doAnswer { File.createTempFile("test-", ".download").also { temps += it } }
        on { writeAttachmentFromFile(any(), any()) } doAnswer { written = it.getArgument<File>(0).readText(); mock() }
    }
    private lateinit var api: ZoteroAPI

    private val attachment = Item().apply {
        itemInfo = ItemInfo("ATTA2345", version = 1, deleted = false)
        itemData = listOf(
            ItemData("ATTA2345", "itemType", "attachment", "String"),
            ItemData("ATTA2345", "filename", "a.pdf", "String"),
        )
        creators = emptyList()
        tags = emptyList()
        collections = emptyList()
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = ZoteroAPI("key", "1", "user", storage, mock<PreferenceManager>(), baseUrl = server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a whole download replaces the copy`() {
        server.enqueue(MockResponse().setBody("%PDF-new"))

        api.downloadItemRx(attachment, context = mock<Context>()).test().awaitDone(5, TimeUnit.SECONDS)
            .assertComplete()

        assertEquals("%PDF-new", written)
        assertFalse(temps.single().exists())
    }

    @Test
    fun `a download cut off midway leaves the copy alone`() {
        server.enqueue(
            MockResponse().setBody("%PDF-" + "x".repeat(200_000))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        api.downloadItemRx(attachment, context = mock<Context>()).test().awaitDone(5, TimeUnit.SECONDS)
            .assertError { true }

        verify(storage, never()).writeAttachmentFromFile(any(), any())
        verify(storage, never()).deleteAttachment(any())
        assertFalse(temps.single().exists())
    }
}
