package com.mickstarify.zooforzotero.ZoteroStorage.Downloads

import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import com.mickstarify.zooforzotero.ZoteroStorage.Database.AttachmentInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase

/**
 * What follows a finished download (ZoteroAPI.downloadItemRx), the same for one file from
 * the attachment menu (LibraryAttachments) and for a bulk download (AttachmentDownloadWorker).
 *
 * downloadItemRx has checked the temporary file against [expected] before copying it into
 * storage; the copy is hashed again because writing into a chosen folder goes through its
 * provider, which can still get it wrong, and because the md5 is recorded as the copy's base
 * (an empty [expected], Zotero having none, passes either check).
 *
 * @return the copy's md5, recorded as its base; null if it doesn't match [expected], in
 *   which case it has been deleted and nothing is recorded. Blocking: not on the main thread.
 */
fun settleDownload(
    item: Item, group: Int, expected: String,
    storage: AttachmentStorageManager, database: ZoteroDatabase,
): String? {
    val md5 = storage.calculateMd5(item)
    if (expected.isNotEmpty() && !md5.equals(expected, ignoreCase = true)) {
        storage.deleteAttachment(item)
        return null
    }
    database.setAttachmentBase(item.itemKey, group, md5)
    return md5
}

/** Where a download of [group]'s came from: WebDAV is for My Library only. */
fun downloadSource(group: Int, webdavEnabled: Boolean): String =
    if (group == GroupInfo.NO_GROUP_ID && webdavEnabled) AttachmentInfo.WEBDAV else AttachmentInfo.ZOTEROAPI
