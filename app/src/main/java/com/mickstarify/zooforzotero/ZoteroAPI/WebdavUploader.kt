package com.mickstarify.zooforzotero.ZoteroAPI

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** An attachment's KEY.prop as read from the WebDAV server before an upload. */
data class RemoteProp(val hash: String, val etag: String?, val raw: String)

/**
 * Replaces an attachment file in Zotero's WebDAV layout (KEY.zip + KEY.prop) so
 * that a concurrent change by another client is detected rather than overwritten,
 * and the server never has a missing zip or a .prop describing the wrong zip.
 *
 * Zotero desktop treats KEY.prop as the commit marker: it decides whether the
 * remote file changed from the .prop, and treats a missing .prop as "no remote
 * file". The sequence mirrors its own upload and adds conditional requests:
 *
 * 1. stage   PUT  KEY_NEW.zip                       (invisible to other clients)
 * 2. claim   DELETE KEY.prop  If-Match: <etag read before the upload>
 *            -> 412: someone changed the file; stop, nothing visible changed
 * 3. swap    MOVE KEY_NEW.zip -> KEY.zip, Overwrite: T  (one rename; no gap)
 * 4. commit  PUT  KEY.prop  If-None-Match: *
 *            -> 412: another client re-created .prop meanwhile; report conflict
 *
 * Optionally [backupCurrent] first keeps a copy of the zip being replaced.
 *
 * The Zotero item's md5/mtime are patched by the caller afterwards, as desktop
 * does. WebDAV LOCK is deliberately not used: desktop doesn't use locks, and a lock
 * left by a crashed upload would block desktop until it expires.
 */
class WebdavUploader(
    private val client: OkHttpClient,
    address: String,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val base = address.trimEnd('/')

    /** Sibling of the Zotero folder; Zotero clients never read or clean it. */
    val backupDir = base.substringBeforeLast('/') + "/zotero-backup"

    private fun url(name: String) = "$base/$name"

    /** The attachment's current .prop, or null if there is none. */
    fun readProp(itemKey: String): RemoteProp? {
        val request = Request.Builder().url(url("${itemKey.uppercase()}.prop")).get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) {
                throw IOException("WebDAV GET .prop failed: HTTP ${response.code}")
            }
            val raw = response.body?.string() ?: ""
            return RemoteProp(WebdavProp(raw).hash, response.header("ETag"), raw)
        }
    }

    /**
     * Copies the current KEY.zip to [backupDir] as KEY-<UTC timestamp>.zip, so a bad
     * upload can be undone by hand. Does nothing if there is no remote zip.
     * @return the backup's URL, or null if there was nothing to back up
     */
    fun backupCurrent(itemKey: String): String? {
        val key = itemKey.uppercase()
        if (!exists(url("$key.zip"))) return null
        val mkcol = send(Request.Builder().url("$backupDir/").method("MKCOL", null).build())
        if (mkcol !in 200..299 && mkcol != 405) { // 405: already exists
            throw IOException("Couldn't create backup folder $backupDir (HTTP $mkcol); nothing was uploaded.")
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now()))
        val target = "$backupDir/$key-$stamp.zip"
        val copy = send(
            Request.Builder().url(url("$key.zip")).method("COPY", null)
                .header("Destination", target).header("Overwrite", "F").build()
        )
        if (copy !in 200..299) {
            throw IOException("Backup copy failed (HTTP $copy); nothing was uploaded.")
        }
        return target
    }

    /**
     * Replaces the attachment's zip and .prop. [expected] is the .prop read before
     * the upload decision (null if there was none); [newProp] is the serialized
     * .prop for [zip].
     * @throws RemoteAttachmentChangedException if another client changed the file
     */
    fun replace(itemKey: String, zip: File, newProp: String, expected: RemoteProp?) {
        val key = itemKey.uppercase()
        val zipName = "$key.zip"
        val staged = "${key}_NEW.zip"
        val propName = "$key.prop"

        // 1. stage (clear any leftover from an interrupted earlier attempt first)
        send(Request.Builder().url(url(staged)).delete().build())
        requireOk("stage $staged", send(
            Request.Builder().url(url(staged)).put(zip.asRequestBody(ZIP)).build()
        ))

        // 2. claim
        if (expected != null) {
            val code = if (expected.etag != null) {
                send(Request.Builder().url(url(propName)).delete()
                    .header("If-Match", expected.etag).build())
            } else {
                // server gives no ETag: best effort, re-read and compare just before
                if (readProp(key)?.hash != expected.hash) 412
                else send(Request.Builder().url(url(propName)).delete().build())
            }
            if (code == 412 || code == 404) {
                send(Request.Builder().url(url(staged)).delete().build())
                throw RemoteAttachmentChangedException(
                    "The file on your WebDAV server changed while uploading. Nothing was replaced."
                )
            }
            requireOk("claim $propName", code)
        }

        // 3. swap
        val move = send(
            Request.Builder().url(url(staged)).method("MOVE", null)
                .header("Destination", url(zipName)).header("Overwrite", "T").build()
        )
        if (move !in 200..299) {
            // the zip is untouched; put the old .prop back so other clients see no change
            if (expected != null) {
                send(Request.Builder().url(url(propName))
                    .put(expected.raw.toRequestBody(TEXT)).header("If-None-Match", "*").build())
            }
            send(Request.Builder().url(url(staged)).delete().build())
            throw IOException("WebDAV MOVE failed (HTTP $move); the previous file is unchanged.")
        }

        // 4. commit
        val put = send(
            Request.Builder().url(url(propName)).put(newProp.toRequestBody(TEXT))
                .header("If-None-Match", "*").build()
        )
        if (put == 412) {
            throw RemoteAttachmentChangedException(
                "Another device uploaded this attachment at the same moment. " +
                        "Check it in Zotero desktop before uploading again."
            )
        }
        requireOk("commit $propName", put)
    }

    private fun exists(url: String): Boolean {
        val code = send(Request.Builder().url(url).head().build())
        if (code == 404) return false
        requireOk("HEAD $url", code)
        return true
    }

    private fun send(request: Request): Int =
        client.newCall(request).execute().use { it.code }

    private fun requireOk(step: String, code: Int) {
        if (code !in 200..299) throw IOException("WebDAV $step failed: HTTP $code")
    }

    private companion object {
        val ZIP = "application/zip".toMediaType()
        val TEXT = "text/plain".toMediaType()
    }
}
