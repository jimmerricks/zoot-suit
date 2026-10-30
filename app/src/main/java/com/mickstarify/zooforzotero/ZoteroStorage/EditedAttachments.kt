package com.mickstarify.zooforzotero.ZoteroStorage

import com.mickstarify.zooforzotero.ZoteroStorage.Database.FileHash
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import java.io.FileNotFoundException

/**
 * A library's attachments whose file is on this device, and which of them were edited
 * here and not uploaded yet (see EditedAttachmentFinder), read from the database and the
 * attachment folder. The library is [groupId], read at each call: the library screen
 * asks about the open library, Settings about My Library before signing out. Blocking;
 * call off the main thread.
 */
class EditedAttachments(
    private val database: ZoteroDatabase,
    private val storage: AttachmentStorageManager,
    private val groupId: () -> Int = { GroupInfo.NO_GROUP_ID },
) {
    /**
     * The library's attachments (not trashed) whose folder is on this device, by key.
     * Kept for [attachmentItem] until the next call.
     */
    fun downloaded(): Map<String, Item> {
        val group = groupId()
        val items = libraryItems(group, storage.listDownloadedItemKeys())
        downloadedCache = group to items
        return items
    }

    @Volatile
    private var downloadedCache: Pair<Int, Map<String, Item>> = GroupInfo.NO_GROUP_ID to emptyMap()

    /** Attachments whose file is on this device (non-empty), by key. */
    fun onDevice(): Map<String, Item> = downloaded().filterValues { item ->
        storage.getFileSize(item).getOrDefault(0L) > 0
    }

    /**
     * Attachments edited on this device and not uploaded yet. Hashes files whose size or
     * mtime changed since they were last hashed.
     */
    fun edited(): List<Item> {
        val downloaded = downloaded()
        val group = groupId()
        val bases = HashMap<String, String>()
        for (key in downloaded.keys) {
            database.getAttachmentBase(key, group)?.let { bases[key] = it }
        }
        return finder.edited(bases).mapNotNull { downloaded[it] }
    }

    /** md5 of [itemKey]'s file on the device now, null if there's none or it can't be read. */
    fun currentMd5(itemKey: String): String? = finder.currentMd5(itemKey)

    /** md5 of the file this device last downloaded/uploaded for [item]; see AttachmentBase. */
    fun base(item: Item): String? = database.getAttachmentBase(item.itemKey, groupId())

    fun recordBase(item: Item, md5: String) {
        if (md5.isNotEmpty()) database.setAttachmentBase(item.itemKey, groupId(), md5)
    }

    /** Whether [item]'s file has edits that aren't uploaded; false if that can't be told. */
    fun isEdited(item: Item): Boolean {
        val base = base(item) ?: return false
        return currentMd5(item.itemKey)?.equals(base, ignoreCase = true) == false
    }

    /**
     * Gives downloaded copies from before AttachmentBase existed a base: first from
     * AttachmentInfo while it still has a row, then, for files still without one, Zotero's
     * md5 if the file provably matches it. A copy that matches neither (edited before the
     * upgrade, and its AttachmentInfo already wiped by a sync) stays without a base and
     * falls back to the weaker check. Only files lacking a base are hashed.
     */
    fun adoptBases(group: Int) {
        database.backfillAttachmentBases()
        val withBase = database.attachmentKeysWithBase(group)
        val onDisk = storage.listDownloadedItemKeys() - withBase
        if (onDisk.isEmpty()) return
        libraryItems(group, onDisk).values.filter { it.itemType == Item.ATTACHMENT_TYPE }
            .forEach { item ->
                val zoteroMd5 = item.getMd5Key()
                if (zoteroMd5.isEmpty()) return@forEach
                val local = runCatching { storage.calculateMd5(item) }.getOrNull()
                if (local != null && local.equals(zoteroMd5, ignoreCase = true)) {
                    database.setAttachmentBase(item.itemKey, group, local)
                }
            }
    }

    /** The library's attachment [key] (not trashed), or null; from the last [downloaded], else the database. */
    private fun attachmentItem(key: String): Item? {
        val group = groupId()
        val (cachedGroup, cached) = downloadedCache
        if (cachedGroup == group) cached[key]?.let { return it }
        return libraryItems(group, listOf(key))[key]
    }

    /** Library [group]'s items (not trashed) among [keys], by key, from the database. */
    private fun libraryItems(group: Int, keys: Collection<String>): Map<String, Item> =
        database.loadItemsByKeys(keys.toList(), withChildren = false)
            .filter { it.getGroup() == group && !it.itemInfo.deleted }
            .associateBy { it.itemKey }

    private val finder = EditedAttachmentFinder(
        stampOf = { key ->
            attachmentItem(key)?.let { item ->
                runCatching {
                    Pair(storage.getFileSize(item).getOrThrow(), storage.getMtime(item))
                }.getOrNull()
            }
        },
        md5Of = { key ->
            storage.calculateMd5(attachmentItem(key) ?: throw FileNotFoundException(key))
        },
        store = object : EditedAttachmentFinder.Store {
            override fun get(itemKey: String) = database.getFileHash(itemKey)
                ?.let { EditedAttachmentFinder.Cached(it.size, it.mtime, it.md5) }

            override fun put(itemKey: String, cached: EditedAttachmentFinder.Cached) =
                database.putFileHash(FileHash(itemKey, cached.size, cached.mtime, cached.md5))

            override fun remove(itemKey: String) = database.deleteFileHash(itemKey)
        }
    )
}
