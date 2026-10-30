package com.mickstarify.zooforzotero.ZoteroStorage.Downloads

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Bulk downloads of attachments: queued here, downloaded by [AttachmentDownloadWorker] in
 * the background, one round at a time.
 */
object AttachmentDownloads {
    private const val WORK = "attachment-downloads"
    private const val QUEUE_FILE = "attachment-downloads.json"

    fun queue(context: Context) =
        AttachmentDownloadQueue(File(context.applicationContext.filesDir, QUEUE_FILE))

    /**
     * Queues the attachments [entries] and makes sure a worker will take them; returns how
     * many weren't queued already. A worker already running takes them in its round.
     */
    fun enqueue(context: Context, entries: List<AttachmentDownloadQueue.Entry>): Int {
        val added = queue(context).add(entries)
        val request = OneTimeWorkRequestBuilder<AttachmentDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        // after a running one rather than instead of it: one that is just finishing could
        // otherwise miss the entries added now
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        return added
    }

    /** Stops the round: what's queued is dropped; a file being downloaded is finished. */
    fun stop(context: Context) {
        queue(context).clear()
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /**
     * Forgets bulk downloads altogether (signing out): the worker is cancelled, waiting up to
     * a few seconds for WorkManager to store that, as the process exits next; the queue and
     * its tally are deleted.
     */
    fun forget(context: Context) {
        val cancelled = WorkManager.getInstance(context).cancelUniqueWork(WORK)
        runBlocking { withTimeoutOrNull(5_000) { runCatching { cancelled.await() } } }
        val file = File(context.applicationContext.filesDir, QUEUE_FILE)
        file.delete()
        File(file.path + ".tmp").delete()
    }

    /** The workers of bulk downloads, for their state and progress. */
    fun workInfos(context: Context): LiveData<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(WORK)
}
