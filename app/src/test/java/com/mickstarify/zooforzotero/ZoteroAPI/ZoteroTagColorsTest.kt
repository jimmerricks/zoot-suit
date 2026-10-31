package com.mickstarify.zooforzotero.ZoteroAPI

import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/** Fetching a library's "tagColors" setting, as a sync does beside the items. */
class ZoteroTagColorsTest {
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

    @Test
    fun theSettingsValueIsReturnedAsJson() {
        server.enqueue(MockResponse().setBody(
            """{"value": [{"name": "to read", "color": "#FF6666"}], "version": 42}"""
        ))
        assertEquals("""[{"name":"to read","color":"#FF6666"}]""", api.getTagColors(GroupInfo.NO_GROUP_ID))
        assertEquals("/users/1/settings/tagColors", server.takeRequest().path)
    }

    @Test
    fun aGroupAsksForItsOwnSetting() {
        server.enqueue(MockResponse().setBody("""{"value": [], "version": 3}"""))
        assertEquals("[]", api.getTagColors(123))
        assertEquals("/groups/123/settings/tagColors", server.takeRequest().path)
    }

    @Test
    fun aSettingNotSetIsNull() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Setting not found"))
        assertNull(api.getTagColors(GroupInfo.NO_GROUP_ID))
    }

    @Test
    fun aServerErrorThrows() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThrows(Exception::class.java) { api.getTagColors(GroupInfo.NO_GROUP_ID) }
    }
}
