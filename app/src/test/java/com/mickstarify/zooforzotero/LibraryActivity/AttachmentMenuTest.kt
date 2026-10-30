package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.LibraryActivity.AttachmentMenu.Action
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentLocalState
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentMenuTest {
    private fun actions(state: AttachmentLocalState, linkMode: String? = "imported_file", canUpload: Boolean = true, canCheck: Boolean = true) =
        AttachmentMenu.actions(state, linkMode, canUpload, canCheck)

    @Test
    fun `a file not here can be opened or downloaded`() {
        assertEquals(listOf(Action.OPEN, Action.DOWNLOAD), actions(AttachmentLocalState.NOT_DOWNLOADED))
    }

    @Test
    fun `a file here offers a check for a newer one, where the server can be asked`() {
        assertEquals(listOf(Action.OPEN, Action.CHECK_NEWER, Action.DELETE), actions(AttachmentLocalState.ON_DEVICE))
        assertEquals(listOf(Action.OPEN, Action.DELETE), actions(AttachmentLocalState.ON_DEVICE, canCheck = false))
    }

    @Test
    fun `upload only for edits that can go up`() {
        assertEquals(listOf(Action.OPEN, Action.UPLOAD, Action.DELETE), actions(AttachmentLocalState.EDITED))
        assertEquals(listOf(Action.OPEN, Action.DELETE), actions(AttachmentLocalState.EDITED, canUpload = false))
        assertEquals(listOf(Action.OPEN, Action.DOWNLOAD_ZOTEROS, Action.DELETE), actions(AttachmentLocalState.CONFLICT))
    }

    @Test
    fun `an outdated copy offers the newer version`() {
        assertEquals(listOf(Action.OPEN, Action.DOWNLOAD_NEWER, Action.DELETE), actions(AttachmentLocalState.OUTDATED))
    }

    @Test
    fun `links can only be opened`() {
        assertEquals(listOf(Action.OPEN), actions(AttachmentLocalState.NOT_DOWNLOADED, linkMode = "linked_file"))
        assertEquals(listOf(Action.OPEN), actions(AttachmentLocalState.ON_DEVICE, linkMode = "linked_url"))
    }
}
