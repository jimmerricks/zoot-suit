package com.mickstarify.zooforzotero.ZoteroStorage.Downloads

import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentDownloadQueue.Entry
import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentDownloadQueue.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AttachmentDownloadQueueTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "queue.json") }
    private fun queue() = AttachmentDownloadQueue(file)

    private val a = Entry(0, "AAAA2222")
    private val b = Entry(0, "BBBB2222")
    private val c = Entry(7, "CCCC2222")

    @Test
    fun `entries come out in order, once each, and are counted`() {
        val q = queue()
        assertEquals(2, q.add(listOf(a, b)))
        assertEquals(1, q.add(listOf(b, c))) // b is queued already
        assertEquals(a, q.next())
        q.finish(a, Outcome.DOWNLOADED)
        assertEquals(b, q.next())
        q.finish(b, Outcome.FAILED, "damaged")
        assertEquals(c, q.next())
        q.finish(c, Outcome.SKIPPED)
        assertNull(q.next())
        val state = q.state()
        assertEquals(listOf(1, 1, 1, 3, 3), listOf(state.downloaded, state.skipped, state.failed, state.done, state.total))
        assertEquals("damaged", state.firstError)
    }

    @Test
    fun `the queue outlives the object, as it does the process`() {
        queue().add(listOf(a, b))
        queue().next()
        val again = queue().state()
        assertEquals(listOf(a, b), again.pending)
        assertEquals(a, again.inProgress) // cut off: the next run deletes its partial file
    }

    @Test
    fun `a released entry stays first, no longer in progress`() {
        val q = queue()
        q.add(listOf(a, b))
        q.next()
        q.release(a)
        assertNull(q.state().inProgress)
        assertEquals(a, q.next())
    }

    @Test
    fun `adding to an empty queue starts a new tally, adding to a busy one keeps it`() {
        val q = queue()
        q.add(listOf(a, b))
        q.next(); q.finish(a, Outcome.DOWNLOADED)
        q.add(listOf(c))
        assertEquals(3, q.state().total)
        q.next(); q.finish(b, Outcome.DOWNLOADED)
        q.next(); q.finish(c, Outcome.DOWNLOADED)
        q.add(listOf(a))
        assertEquals(0, q.state().downloaded)
        assertEquals(1, q.state().total)
    }

    @Test
    fun `clearing drops what's queued and keeps the tally`() {
        val q = queue()
        q.add(listOf(a, b))
        q.next(); q.finish(a, Outcome.DOWNLOADED)
        q.clear()
        assertNull(q.next())
        assertEquals(1, q.state().downloaded)
    }

    @Test
    fun `an unreadable file is an empty queue`() {
        file.writeText("{not json")
        assertEquals(0, queue().state().total)
        file.writeText("{}")
        assertEquals(0, queue().state().total)
    }

    @Test
    fun `a finished round is reported once, and a new one again`() {
        val q = queue()
        q.add(listOf(a))
        assertNull(q.takeReport()) // not finished
        q.next(); q.finish(a, Outcome.DOWNLOADED)
        assertEquals(1, q.takeReport()?.downloaded)
        assertNull(queue().takeReport())
        q.add(listOf(b))
        q.next(); q.finish(b, Outcome.FAILED, "x")
        assertEquals(1, q.takeReport()?.failed)
    }
}
