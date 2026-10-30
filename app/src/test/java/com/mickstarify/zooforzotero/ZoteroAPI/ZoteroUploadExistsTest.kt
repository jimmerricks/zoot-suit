package com.mickstarify.zooforzotero.ZoteroAPI

import android.net.Uri
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ItemInfo
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.util.concurrent.TimeUnit

/** Zotero's "the file is there already" answer, however it is spaced. */
class ZoteroUploadExistsTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ZoteroAPI

    private val attachment = Item().apply {
        itemInfo = ItemInfo("ATTA2345", version = 1, deleted = false)
        itemData = emptyList()
        creators = emptyList()
        tags = emptyList()
        collections = emptyList()
    }

    private val storage = mock<AttachmentStorageManager> {
        on { getAttachmentUri(any()) } doReturn mock<Uri>()
        on { getMtime(any()) } doReturn 1L
        on { getFilenameForItem(any()) } doReturn "a.pdf"
        on { getFileSize(any<Uri>()) } doReturn 4L
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
    fun `a spaced exists answer is already uploaded`() {
        server.enqueue(MockResponse().setBody("{ \"exists\": 1 }\n"))

        api.updateAttachment(attachment, "old-md5", "new-md5").test().awaitDone(5, TimeUnit.SECONDS)
            .assertError(AlreadyUploadedException::class.java)
    }
}
