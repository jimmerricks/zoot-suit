package com.mickstarify.zooforzotero.ZoteroStorage

import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentOpenCheck.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentOpenCheckTest {
    private val v1 = "6fc64eebe267781778972ade8e5d7e9d" // as downloaded
    private val v2 = "cc96b19940cf55180f7e8bc8d2ee3e33" // changed on desktop
    private val edited = "8f8414b9767e64faa01f95b7e33a5d41" // annotated on this device

    private fun d(base: String?, zotero: String, local: String) = AttachmentOpenCheck.decide(base, zotero, local)

    @Test
    fun `current copy opens, edited or not`() {
        assertEquals(Action.OPEN, d(v1, v1, v1))
        assertEquals(Action.OPEN, d(v1, v1, edited))
    }

    @Test
    fun `stale unedited copy is re-downloaded`() {
        assertEquals(Action.REDOWNLOAD, d(v1, v2, v1))
    }

    @Test
    fun `stale edited copy needs the user's decision`() {
        // the lost-update case: edits made to v1 while Zotero now has v2
        assertEquals(Action.ASK_EDITED_STALE, d(v1, v2, edited))
    }

    @Test
    fun `a copy that already matches Zotero becomes the new base`() {
        assertEquals(Action.RECORD_BASE_AND_OPEN, d(v1, v2, v2))
    }

    @Test
    fun `legacy copies without a base are adopted only when provably current`() {
        assertEquals(Action.RECORD_BASE_AND_OPEN, d(null, v1, v1))
        assertEquals(Action.OPEN, d(null, v1, edited))
    }

    @Test
    fun `no md5 in Zotero means nothing to compare`() {
        assertEquals(Action.OPEN, d(v1, "", edited))
        assertEquals(Action.OPEN, d(null, "", edited))
    }

    @Test
    fun `comparison ignores case`() {
        assertEquals(Action.OPEN, d(v1.uppercase(), v1, v1.uppercase()))
    }
}
