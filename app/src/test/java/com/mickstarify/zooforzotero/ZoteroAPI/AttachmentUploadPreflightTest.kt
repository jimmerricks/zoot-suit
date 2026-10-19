package com.mickstarify.zooforzotero.ZoteroAPI

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentUploadPreflightTest {
    private val synced = "4fa38e3f2c360ca181e633d02bab91f5"
    private val other = "0123456789abcdef0123456789abcdef"

    @Test
    fun `safe when Zotero and WebDAV both still have the synced file`() {
        assertNull(AttachmentUploadPreflight.conflictReason(synced, synced, synced))
    }

    @Test
    fun `hash comparison ignores case`() {
        assertNull(AttachmentUploadPreflight.conflictReason(synced, synced.uppercase(), synced.uppercase()))
    }

    @Test
    fun `conflict when Zotero's md5 changed since the last sync`() {
        assertNotNull(AttachmentUploadPreflight.conflictReason(synced, other, synced))
    }

    @Test
    fun `conflict when the WebDAV prop changed even though Zotero has not caught up`() {
        assertNotNull(AttachmentUploadPreflight.conflictReason(synced, synced, other))
    }

    @Test
    fun `safe when there is no remote file at all`() {
        assertNull(AttachmentUploadPreflight.conflictReason("", null, null))
        assertNull(AttachmentUploadPreflight.conflictReason("", "", null))
    }

    @Test
    fun `conflict when a file appeared remotely that this device never synced`() {
        assertNotNull(AttachmentUploadPreflight.conflictReason("", other, null))
        assertNotNull(AttachmentUploadPreflight.conflictReason("", null, other))
    }

    @Test
    fun `missing prop alone is not a conflict`() {
        assertNull(AttachmentUploadPreflight.conflictReason(synced, synced, null))
    }
}
