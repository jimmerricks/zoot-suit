package com.mickstarify.zooforzotero.ZoteroAPI

import com.mickstarify.zooforzotero.ZoteroAPI.AttachmentUploadBatch.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

class AttachmentUploadBatchTest {

    @Test
    fun `all uploaded is a plain count`() {
        val (title, message, _) = AttachmentUploadBatch.summary(listOf(Outcome("a.pdf"), Outcome("b.pdf")))
        assertEquals("Upload finished", title)
        assertEquals("Uploaded 2 attachments.", message)
    }

    @Test
    fun `one uploaded is singular`() {
        assertEquals("Uploaded 1 attachment.", AttachmentUploadBatch.summary(listOf(Outcome("a.pdf"))).message)
    }

    @Test
    fun `partial failure names each refused file and the count`() {
        val (title, message, _) = AttachmentUploadBatch.summary(
            listOf(Outcome("a.pdf"), Outcome("b.pdf", "changed elsewhere"), Outcome("c.pdf"))
        )
        assertEquals("Some files not uploaded", title)
        assertTrue(message.startsWith("Uploaded 2 of 3."))
        assertTrue(message.contains("b.pdf: changed elsewhere"))
        assertFalse(message.contains("a.pdf"))
    }

    @Test
    fun `a single failure has no count line`() {
        val (title, message, _) = AttachmentUploadBatch.summary(listOf(Outcome("a.pdf", "gone")))
        assertEquals("Nothing uploaded", title)
        assertEquals("a.pdf: gone", message)
    }

    @Test
    fun `offline failures are quiet and promise a retry`() {
        val offline = AttachmentUploadBatch.outcome(
            "b.pdf", RuntimeException(java.net.UnknownHostException("api.zotero.org"))
        )
        assertTrue(offline.offline)
        val summary = AttachmentUploadBatch.summary(listOf(Outcome("a.pdf"), offline))
        assertTrue(summary.quiet)
        assertEquals("Uploaded 1 attachment. Offline: 1 attachment with edits will be uploaded at the next sync.", summary.message)
    }

    @Test
    fun `a real refusal next to an offline failure needs a dialog`() {
        val summary = AttachmentUploadBatch.summary(listOf(
            AttachmentUploadBatch.outcome("a.pdf", java.net.ConnectException()),
            AttachmentUploadBatch.outcome("b.pdf", DamagedAttachmentException("the file is empty"))
        ))
        assertFalse(summary.quiet)
        assertTrue(summary.message.contains("next sync"))
        assertTrue(summary.message.contains("the file is empty"))
    }

    @Test
    fun `offers only when something new has changed`() {
        assertTrue(AttachmentUploadBatch.shouldOffer(listOf("A", "B"), setOf("A")))
        assertFalse(AttachmentUploadBatch.shouldOffer(listOf("A", "B"), setOf("A", "B")))
        assertFalse(AttachmentUploadBatch.shouldOffer(emptyList(), emptySet()))
    }

    @Test
    fun `reasons keep the preflight's explanation`() {
        val reason = AttachmentUploadBatch.failureReason(RemoteAttachmentChangedException("Changed on desktop."))
        assertTrue(reason.startsWith("Changed on desktop."))
        assertTrue(AttachmentUploadBatch.failureReason(DamagedAttachmentException("the file is empty"))
            .contains("the file is empty"))
        assertTrue(AttachmentUploadBatch.failureReason(FileNotFoundException()).contains("no longer"))
    }
}
