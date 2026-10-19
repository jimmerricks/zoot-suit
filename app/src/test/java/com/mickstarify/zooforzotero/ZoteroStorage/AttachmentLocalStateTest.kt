package com.mickstarify.zooforzotero.ZoteroStorage

import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentLocalState.CONFLICT
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentLocalState.EDITED
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentLocalState.NOT_DOWNLOADED
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentLocalState.ON_DEVICE
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentLocalState.OUTDATED
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentLocalStateTest {
    private val base = "aaaa"
    private val other = "bbbb"
    private val edit = "cccc"

    @Test
    fun `no file means not downloaded`() = assertEquals(NOT_DOWNLOADED, AttachmentLocalState.of(null, base, base))

    @Test
    fun `untouched current copy is on the device`() = assertEquals(ON_DEVICE, AttachmentLocalState.of(base, base, base))

    @Test
    fun `local change on the current version is an edit to sync`() =
        assertEquals(EDITED, AttachmentLocalState.of(edit, base, base))

    @Test
    fun `untouched copy of an older version is outdated`() =
        assertEquals(OUTDATED, AttachmentLocalState.of(base, base, other))

    @Test
    fun `edited copy of an older version is a conflict`() =
        assertEquals(CONFLICT, AttachmentLocalState.of(edit, base, other))

    @Test
    fun `copy that already matches Zotero's newer md5 is fine`() =
        assertEquals(ON_DEVICE, AttachmentLocalState.of(other, base, other))

    @Test
    fun `copy without a base can't be judged`() =
        assertEquals(ON_DEVICE, AttachmentLocalState.of(edit, null, base))

    @Test
    fun `unknown Zotero md5 only compares with the base`() {
        assertEquals(ON_DEVICE, AttachmentLocalState.of(base, base, ""))
        assertEquals(EDITED, AttachmentLocalState.of(edit, base, ""))
    }

    @Test
    fun `comparison ignores case`() =
        assertEquals(ON_DEVICE, AttachmentLocalState.of(base.uppercase(), base, base.uppercase()))
}
