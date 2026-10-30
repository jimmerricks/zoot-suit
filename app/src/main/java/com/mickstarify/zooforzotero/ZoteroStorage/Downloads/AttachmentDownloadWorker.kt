package com.mickstarify.zooforzotero.ZoteroStorage.Downloads

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mickstarify.zooforzotero.SyncSetup.domain.AuthenticationStorageImpl
import com.mickstarify.zooforzotero.ZoteroAPI.MAX_ITEMS_PER_WRITE
import com.mickstarify.zooforzotero.ZoteroAPI.NetworkErrors
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroAPI
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroNotFoundException
import com.mickstarify.zooforzotero.ZoteroStorage.Database.AttachmentInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentDownloadQueue.Outcome
import com.mickstarify.zooforzotero.di.SingletonComponentsEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AttachmentDownloads"

/**
 * Downloads the queued attachments ([AttachmentDownloadQueue]) one after another, in the
 * background, so the app can be left meanwhile. Each file is checked against Zotero's md5
 * and recorded as the copy's base, as a download from the attachment menu is. A file
 * already on the device is skipped: bulk downloads fetch what's missing.
 *
 * Runs only with a network (AttachmentDownloads). Offline, or stopped by Android, it asks
 * to be run again and carries on from the queue. A download goes to a temporary file
 * first; one whose process was killed while copying it into place is deleted at the next
 * start, so a partial file is never taken for a whole one.
 */
class AttachmentDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val hilt = EntryPointAccessors.fromApplication(context, SingletonComponentsEntryPoint::class.java)
    private val database = hilt.getZoteroDatabase()
    private val storage = hilt.getAttachmentStorageManager()
    private val preferences = hilt.getPreferences()
    private val queue = AttachmentDownloads.queue(context)

    /** Zotero's md5 for My Library's attachments, read ahead 50 at a time; "" for none. */
    private val latestMd5 = HashMap<String, String>()

    // the downloads block (Rx, file I/O): on the IO threads
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { work() }

    private suspend fun work(): Result {
        val auth = AuthenticationStorageImpl(applicationContext)
        if (!auth.hasCredentials()) {
            queue.clear()
            return Result.failure()
        }
        val api = ZoteroAPI(auth.getUserKey(), auth.getUserID(), auth.getUsername(), storage, preferences)

        queue.state().inProgress?.let { cutOff ->
            // the last run ended during this download, perhaps while copying it into
            // place; its file, if any, may be partial
            load(cutOff)?.let { storage.deleteAttachment(it) }
        }
        while (!isStopped) {
            val entry = queue.next() ?: break
            report()
            try {
                val (outcome, error) = download(api, entry)
                queue.finish(entry, outcome, error)
            } catch (e: Exception) {
                if (NetworkErrors.isOffline(e)) {
                    Log.d(TAG, "offline at ${entry.key}; trying again when there's a network")
                    queue.release(entry) // first again when the network is back
                    return Result.retry()
                }
                Log.e(TAG, "downloading ${entry.key} failed: $e")
                queue.finish(entry, Outcome.FAILED, e.message ?: e.toString())
            }
        }
        report()
        return if (isStopped) Result.retry() else Result.success()
    }

    private suspend fun report() {
        val state = queue.state()
        setProgress(workDataOf(DONE to state.done, TOTAL to state.total))
    }

    private fun load(entry: AttachmentDownloadQueue.Entry): Item? =
        database.loadItemsByKeys(listOf(entry.key), listFieldsOnly = false, withChildren = false).firstOrNull()

    /** Downloads [entry]'s file; the outcome, and why if it failed. Throws when offline. */
    private fun download(api: ZoteroAPI, entry: AttachmentDownloadQueue.Entry): Pair<Outcome, String?> {
        val item = load(entry) ?: return Outcome.SKIPPED to null // gone from the library since
        val exists = runCatching { storage.checkIfAttachmentExists(item, checkMd5 = false) }.getOrDefault(false)
        if (exists) return Outcome.SKIPPED to null

        // Zotero's md5 now; the one from the last sync is stale for a file changed since
        val latest = if (entry.group == GroupInfo.NO_GROUP_ID) latestMd5Of(api, entry.key) else null
        val expected = latest ?: item.data["md5"] ?: ""
        try {
            api.downloadItemRx(item, entry.group, applicationContext).ignoreElements().blockingAwait()
        } catch (e: Exception) {
            // a failed download never reaches storage (it goes through a temporary file)
            if (generateSequence<Throwable>(e) { it.cause }.any { it is ZoteroNotFoundException }) {
                return Outcome.FAILED to "A file isn't in Zotero's storage (or on your WebDAV server)."
            }
            throw e
        }
        val md5 = storage.calculateMd5(item)
        if (expected.isNotEmpty() && !md5.equals(expected, ignoreCase = true)) {
            storage.deleteAttachment(item)
            return Outcome.FAILED to "A download didn't match Zotero's checksum and was deleted."
        }
        database.setAttachmentBase(item.itemKey, entry.group, md5)
        val source = if (entry.group == GroupInfo.NO_GROUP_ID && preferences.isWebDAVEnabled()) {
            AttachmentInfo.WEBDAV
        } else {
            AttachmentInfo.ZOTEROAPI
        }
        database.writeAttachmentInfo(AttachmentInfo(item.itemKey, entry.group, md5, item.getMtime(), source))
            .blockingAwait()
        return Outcome.DOWNLOADED to null
    }

    /**
     * Zotero's md5 for [key], reading it with those of the next queued attachments of My
     * Library in one request; null if Zotero doesn't have the item.
     */
    private fun latestMd5Of(api: ZoteroAPI, key: String): String? {
        if (key !in latestMd5) {
            val ahead = queue.state().pending.filter { it.group == GroupInfo.NO_GROUP_ID }.map { it.key }
            val keys = (listOf(key) + ahead.filter { it != key && it !in latestMd5 }).take(MAX_ITEMS_PER_WRITE)
            val items = api.getItemsData(keys)
            for (k in keys) {
                latestMd5[k] = items[k]?.second?.get("md5")?.takeUnless { it.isJsonNull }?.asString ?: ""
            }
        }
        return latestMd5[key]?.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val DONE = "done"
        const val TOTAL = "total"
    }
}
