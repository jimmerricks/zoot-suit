package com.mickstarify.zooforzotero.ZoteroStorage

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase

/**
 * Every attachment file on this device, in all libraries, trashed items' included: what
 * Settings counts before the attachment folder changes. Blocking; call off the main thread.
 */
class FilesOnDevice(
    private val database: ZoteroDatabase,
    private val storage: AttachmentStorageManager,
) {
    /** The files and their total size in bytes. */
    data class Files(val attachments: List<Item>, val bytes: Long)

    fun list(): Files {
        val keys = storage.listDownloadedItemKeys()
        if (keys.isEmpty()) return Files(emptyList(), 0)
        val attachments = database.loadItemsByKeys(keys.toList(), withChildren = false)
            .filter { it.itemType == Item.ATTACHMENT_TYPE }
        val bytes = attachments.sumOf { storage.getFileSize(it).getOrDefault(0L) }
        return Files(attachments, bytes)
    }
}
