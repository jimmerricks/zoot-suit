package com.mickstarify.zooforzotero.ZoteroAPI

/** Thrown before any upload when the remote file is not the one this device last synced. */
class RemoteAttachmentChangedException(message: String) : RuntimeException(message)

/**
 * Decides whether uploading a locally modified attachment would overwrite a newer
 * remote file.
 *
 * A WebDAV upload replaces KEY.zip/KEY.prop before Zotero's item metadata is
 * updated, and only that metadata update is version-checked by the API. So the
 * check has to happen first, the way Zotero desktop does it: the file is safe to
 * replace only if both Zotero's md5 and the WebDAV .prop hash still match the md5
 * this device last synced.
 */
object AttachmentUploadPreflight {

    /**
     * @param syncedMd5 md5 from the attachment item as of this device's last sync
     *   ("" if it had no file then)
     * @param serverMd5 md5 on the attachment item on Zotero's server now (null or ""
     *   if it has none)
     * @param webdavHash hash in KEY.prop on the WebDAV server now (null if there is
     *   no .prop)
     * @return null if the upload is safe, otherwise why it isn't
     */
    fun conflictReason(syncedMd5: String, serverMd5: String?, webdavHash: String?): String? {
        val synced = syncedMd5.lowercase()
        if (!serverMd5.isNullOrEmpty() && serverMd5.lowercase() != synced) {
            return "Zotero has a different version of this file than the one you opened " +
                    "(it was changed on another device)."
        }
        if (!webdavHash.isNullOrEmpty() && webdavHash.lowercase() != synced) {
            return "The file on your WebDAV server is different from the one you opened " +
                    "(it was changed on another device)."
        }
        return null
    }
}
