package com.mickstarify.zooforzotero.ZoteroAPI

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import com.mickstarify.zooforzotero.ZoteroStorage.Database.AttachmentInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.EditedAttachments
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.ZoteroDB
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single

/**
 * Uploads one attachment edited on this device, to WebDAV or Zotero's file storage, after
 * checking that Zotero's copy is still the one the edit started from and that the file
 * isn't damaged; then records the upload as the copy's new base. No UI: the library
 * screen runs batches and reports.
 *
 * @param api the API client, built on first use (see LibraryComponents)
 * @param db the open library's ZoteroDB, read at each upload
 */
class AttachmentUploader(
    private val context: Context,
    private val api: () -> ZoteroAPI,
    private val storage: AttachmentStorageManager,
    private val preferences: PreferenceManager,
    private val edited: EditedAttachments,
    private val db: () -> ZoteroDB,
) {
    /** Uploads one edited attachment and records it as synced. Subscribe on io. */
    fun upload(attachment: Item): Completable = Completable.defer {
        // throws FileNotFoundException if the copy is gone
        val md5Key = storage.calculateMd5(attachment)
        fileCheckProblem(attachment)?.let { throw DamagedAttachmentException(it) }
        var mtime = storage.getMtime(attachment)
        if (mtime < attachment.getMtime()) {
            Log.e("zotero", "for some reason our mtime is older than the original???")
            mtime = attachment.getMtime()
        }
        val webdav = preferences.isWebDAVEnabled()
        val upload = if (webdav) {
            uploadWithWebdav(attachment, md5Key, mtime)
        } else {
            val baseMd5 = edited.base(attachment) ?: attachment.getMd5Key()
            api().updateAttachment(attachment, baseMd5).onErrorResumeNext { e ->
                if (e is AlreadyUploadedException) Completable.complete() else Completable.error(e)
            }
        }
        upload.andThen(Completable.fromAction {
            edited.recordBase(attachment, md5Key)
            db().updateAttachmentMetadata(
                attachment.itemKey,
                md5Key,
                storage.getMtime(attachment),
                if (webdav) AttachmentInfo.WEBDAV else AttachmentInfo.ZOTEROAPI
            ).blockingAwait()
        })
    }

    private fun uploadWithWebdav(attachment: Item, md5Key: String, mtime: Long): Completable =
        // Check for remote changes BEFORE touching the WebDAV server: the upload
        // replaces KEY.zip/.prop outright, and only the later metadata patch is
        // version-checked by Zotero. See AttachmentUploadPreflight.
        Single.fromCallable {
            val (serverVersion, serverMd5) =
                api().getAttachmentFileState(attachment.itemKey).blockingGet()
            val remoteProp = api().getWebdavRemoteProp(attachment, context)
            // the version this device's edits are based on; for copies downloaded
            // before bases were recorded, the md5 as of the last sync (weaker)
            val baseMd5 = edited.base(attachment) ?: attachment.getMd5Key()
            AttachmentUploadPreflight.conflictReason(
                baseMd5, serverMd5, remoteProp?.hash
            )?.let { throw RemoteAttachmentChangedException(it) }
            Pair(serverVersion, remoteProp)
        }.flatMapCompletable { (serverVersion, remoteProp) ->
            // the .prop's ETag makes the WebDAV replace conditional; see WebdavUploader
            api().uploadAttachmentWithWebdav(
                attachment, context, remoteProp, md5Key, mtime
            ).andThen(
                Completable.fromAction {
                    val modificationJsonObject = JsonObject()
                    modificationJsonObject.addProperty("md5", md5Key)
                    modificationJsonObject.addProperty("mtime", mtime)
                    // the version just checked, so attachment edits that didn't touch
                    // the file (e.g. a tag) don't fail the patch after the upload
                    api().patchItem(attachment, modificationJsonObject, serverVersion)
                        .blockingAwait()
                }
            )
        }

    /** Reads the first and last few bytes and runs [AttachmentFileCheck]. */
    private fun fileCheckProblem(attachment: Item): String? {
        val window = AttachmentFileCheck.WINDOW
        val size = storage.getFileSize(
            storage.getAttachmentUri(attachment)
        )
        fun readUpTo(stream: java.io.InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = stream.read(buf, read, n - read)
                if (r < 0) break
                read += r
            }
            return buf.copyOf(read)
        }
        val head = storage.getItemInputStream(attachment).use { readUpTo(it, window) }
        val tail = storage.getItemInputStream(attachment).use { stream ->
            var toSkip = maxOf(0L, size - window)
            while (toSkip > 0) {
                val skipped = stream.skip(toSkip)
                if (skipped <= 0) break
                toSkip -= skipped
            }
            readUpTo(stream, window)
        }
        val isPdf = attachment.data["contentType"] == "application/pdf" ||
                storage.getFilenameForItem(attachment).endsWith(".pdf", ignoreCase = true)
        return AttachmentFileCheck.problem(head, tail, size, isPdf)
    }
}
