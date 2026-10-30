package com.mickstarify.zooforzotero.ZoteroStorage.Downloads

import com.google.gson.Gson
import java.io.File

/**
 * The attachments waiting to be downloaded (AttachmentDownloadWorker), with a tally of
 * what this round of downloads did, kept in [file] so that they outlive the process: a
 * worker that Android stops or kills is run again and carries on where it was. A round
 * starts when attachments are added to an empty queue. Thread-safe within the process.
 */
class AttachmentDownloadQueue(private val file: File) {

    data class Entry(val group: Int, val key: String)

    enum class Outcome { DOWNLOADED, SKIPPED, FAILED }

    data class State(
        val pending: List<Entry> = emptyList(),
        val downloaded: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        /** The first failure's reason, for the message at the end. */
        val firstError: String? = null,
        /** The entry being downloaded; still set at a worker's start, its file is partial. */
        val inProgress: Entry? = null,
        /** Whether the user has been told how the finished round went. */
        val reported: Boolean = false,
        /** Whether the round was stopped by the user before its end. */
        val stopped: Boolean = false,
    ) {
        val done get() = downloaded + skipped + failed
        val total get() = done + pending.size
    }

    fun state(): State = synchronized(LOCK) { read() }

    /** Queues [entries] not queued already; returns how many were added. */
    fun add(entries: List<Entry>): Int = synchronized(LOCK) {
        val state = read()
        // a new round: the last one's tally is forgotten
        val base = if (state.pending.isEmpty() && state.inProgress == null) State() else state
        val queued = base.pending.toHashSet()
        val added = entries.filter { queued.add(it) }
        write(base.copy(pending = base.pending + added))
        added.size
    }

    /** The next entry to download, marked as in progress; null when there is none. */
    fun next(): Entry? = synchronized(LOCK) {
        val state = read()
        val next = state.pending.firstOrNull() ?: return null
        write(state.copy(inProgress = next))
        next
    }

    /** [entry] is done with: off the queue, counted as [outcome]. */
    fun finish(entry: Entry, outcome: Outcome, error: String? = null) = synchronized(LOCK) {
        val state = read()
        write(
            state.copy(
                pending = state.pending - entry,
                downloaded = state.downloaded + if (outcome == Outcome.DOWNLOADED) 1 else 0,
                skipped = state.skipped + if (outcome == Outcome.SKIPPED) 1 else 0,
                failed = state.failed + if (outcome == Outcome.FAILED) 1 else 0,
                firstError = state.firstError ?: error,
                inProgress = null,
            )
        )
    }

    /** [entry] wasn't done (offline): it stays queued, no longer in progress. */
    fun release(entry: Entry) = synchronized(LOCK) {
        val state = read()
        if (state.inProgress == entry) write(state.copy(inProgress = null))
    }

    /** The finished round's tally, once: null if it was reported already or isn't finished. */
    fun takeReport(): State? = synchronized(LOCK) {
        val state = read()
        if (state.reported || state.pending.isNotEmpty() || state.inProgress != null || state.done == 0) return null
        write(state.copy(reported = true))
        state
    }

    /** Empties the queue (stopped by the user); the tally stays for the message. */
    fun clear() = synchronized(LOCK) {
        val state = read()
        write(state.copy(pending = emptyList(), inProgress = null, stopped = state.pending.isNotEmpty()))
    }

    @Suppress("SENSELESS_COMPARISON") // Gson leaves a missing list null despite its type
    private fun read(): State = try {
        val state = if (file.exists()) Gson().fromJson(file.readText(), State::class.java) else null
        if (state == null || state.pending == null) State() else state
    } catch (e: Exception) {
        State() // unreadable: start over rather than fail every download
    }

    private fun write(state: State) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(Gson().toJson(state))
        tmp.renameTo(file)
    }

    private companion object {
        val LOCK = Any()
    }
}
