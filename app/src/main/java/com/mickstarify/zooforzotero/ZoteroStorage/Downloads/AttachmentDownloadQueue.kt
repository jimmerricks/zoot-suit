package com.mickstarify.zooforzotero.ZoteroStorage.Downloads

import com.google.gson.Gson
import java.io.File

/**
 * The attachments waiting to be downloaded (AttachmentDownloadWorker), with a tally of
 * what this round of downloads did, kept in [file] and a file beside it so that they
 * outlive the process: a worker that Android stops or kills is run again and carries on
 * where it was. A round starts when attachments are added to an empty queue. Thread-safe
 * within the process.
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

    /**
     * The list as last written whole ([file]): by [add] and [clear], and from 0.5.0. What
     * happens to it one entry at a time is in [Progress], beside it: a whole library's list
     * is thousands of entries, and rewriting it for every file made a bulk download write
     * gigabytes. [generation] pairs the two: a progress from another list is ignored.
     */
    private data class Stored(
        val generation: Long = 0,
        val pending: List<Entry> = emptyList(),
        val downloaded: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val firstError: String? = null,
        val inProgress: Entry? = null,
        val reported: Boolean = false,
        val stopped: Boolean = false,
    )

    /** Where the round stands on [Stored]'s list: [taken] entries from its start are done. */
    private data class Progress(
        val generation: Long = 0,
        val taken: Int = 0,
        val downloaded: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val firstError: String? = null,
        val inProgress: Entry? = null,
        val reported: Boolean = false,
        val stopped: Boolean = false,
    )

    private val progressFile = File(file.path + ".progress")

    fun state(): State = synchronized(LOCK) { stateOf(stored(), progress()) }

    /** Queues [entries] not queued already; returns how many were added. */
    fun add(entries: List<Entry>): Int = synchronized(LOCK) {
        val state = state()
        // a new round: the last one's tally is forgotten
        val base = if (state.pending.isEmpty() && state.inProgress == null) State() else state
        val queued = base.pending.toHashSet()
        val added = entries.filter { queued.add(it) }
        rewrite(base.copy(pending = base.pending + added))
        added.size
    }

    /** The next entry to download, marked as in progress; null when there is none. */
    fun next(): Entry? = synchronized(LOCK) {
        val progress = progress()
        val next = stored().pending.getOrNull(progress.taken) ?: return null
        writeProgress(progress.copy(inProgress = next))
        next
    }

    /** [entry] is done with: off the queue, counted as [outcome]. */
    fun finish(entry: Entry, outcome: Outcome, error: String? = null) = synchronized(LOCK) {
        val progress = progress()
        val counted = progress.copy(
            downloaded = progress.downloaded + if (outcome == Outcome.DOWNLOADED) 1 else 0,
            skipped = progress.skipped + if (outcome == Outcome.SKIPPED) 1 else 0,
            failed = progress.failed + if (outcome == Outcome.FAILED) 1 else 0,
            firstError = progress.firstError ?: error,
            inProgress = null,
        )
        val stored = stored()
        if (stored.pending.getOrNull(progress.taken) == entry) {
            writeProgress(counted.copy(taken = progress.taken + 1))
        } else {
            // not the next one (never so from the worker): the list itself changes
            val state = stateOf(stored, counted)
            rewrite(state.copy(pending = state.pending - entry))
        }
    }

    /** [entry] wasn't done (offline): it stays queued, no longer in progress. */
    fun release(entry: Entry) = synchronized(LOCK) {
        val progress = progress()
        if (progress.inProgress == entry) writeProgress(progress.copy(inProgress = null))
    }

    /**
     * [entry]'s file was put in place whole by another download (one from the attachment
     * menu): if a stopped worker left it in progress, its copy is no longer the partial one
     * the next worker would delete.
     */
    fun settled(entry: Entry) = release(entry)

    /** The finished round's tally, once: null if it was reported already or isn't finished. */
    fun takeReport(): State? = synchronized(LOCK) {
        val progress = progress()
        val state = stateOf(stored(), progress)
        if (state.reported || state.pending.isNotEmpty() || state.inProgress != null || state.done == 0) return null
        writeProgress(progress.copy(reported = true))
        state
    }

    /** Empties the queue (stopped by the user); the tally stays for the message. */
    fun clear() = synchronized(LOCK) {
        val state = state()
        rewrite(state.copy(pending = emptyList(), inProgress = null, stopped = state.pending.isNotEmpty()))
    }

    /** Deletes the queue and its tally (signing out). */
    fun delete() {
        synchronized(LOCK) {
            for (f in listOf(file, progressFile)) {
                f.delete()
                File(f.path + ".tmp").delete()
            }
            cache.remove(file.path)
        }
    }

    private fun stateOf(stored: Stored, p: Progress) = State(
        stored.pending.drop(p.taken), p.downloaded, p.skipped, p.failed, p.firstError,
        p.inProgress, p.reported, p.stopped,
    )

    /** [stored]'s list as last written, read again only when the file changed. */
    @Suppress("SENSELESS_COMPARISON") // Gson leaves a missing list null despite its type
    private fun stored(): Stored {
        val stamp = file.lastModified() to file.length()
        cache[file.path]?.let { (cachedStamp, stored) -> if (cachedStamp == stamp) return stored }
        val stored = try {
            if (file.exists()) Gson().fromJson(file.readText(), Stored::class.java) else null
        } catch (e: Exception) {
            null // unreadable: start over rather than fail every download
        }
        return (if (stored == null || stored.pending == null) Stored() else stored)
            .also { cache[file.path] = stamp to it }
    }

    /** The progress on [stored]'s list; none yet (or for another list): as the list says. */
    private fun progress(): Progress {
        val stored = stored()
        val progress = try {
            if (progressFile.exists()) Gson().fromJson(progressFile.readText(), Progress::class.java) else null
        } catch (e: Exception) {
            null
        }
        return progress?.takeIf { it.generation == stored.generation } ?: Progress(
            stored.generation, 0, stored.downloaded, stored.skipped, stored.failed,
            stored.firstError, stored.inProgress, stored.reported, stored.stopped,
        )
    }

    /** Writes [state] whole, as a new list. Its progress is in the list, so none is kept. */
    private fun rewrite(state: State) {
        val stored = Stored(
            stored().generation + 1, state.pending, state.downloaded, state.skipped, state.failed,
            state.firstError, state.inProgress, state.reported, state.stopped,
        )
        // a crash between the two leaves the old progress, of another generation: ignored
        writeAtomically(file, Gson().toJson(stored))
        cache[file.path] = (file.lastModified() to file.length()) to stored
        writeProgress(progress())
    }

    private fun writeProgress(progress: Progress) = writeAtomically(progressFile, Gson().toJson(progress))

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(target)
    }

    private companion object {
        val LOCK = Any()
        // the lists by file, with the file's mtime and size when read: every queue object of
        // a file shares it, and one that wrote it changes both
        val cache = HashMap<String, Pair<Pair<Long, Long>, Stored>>()
    }
}
