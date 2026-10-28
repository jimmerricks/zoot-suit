package com.mickstarify.zooforzotero.ZoteroStorage

/**
 * Finds attachments edited on this device: downloaded files that differ from the version
 * this device last downloaded or uploaded (their AttachmentBase). Comparing files, rather
 * than remembering which attachments were opened through the app, also catches edits made
 * by opening a PDF another way (a viewer's recent-files list, a file manager) and survives
 * restarts. Copies without a base can't be judged and are left out.
 *
 * Hashing every downloaded PDF on each check would be slow, so md5s are cached per file,
 * keyed by size and modification time; a file whose size or mtime changed is hashed again.
 * The cache is also kept in [store], so that a new process (each start, and each return
 * to the app after Android ended it) doesn't hash every file again. Without a known mtime
 * (0) a hash is only kept in memory.
 *
 * @param stampOf size and mtime of the file for an item key, null if it isn't on the device
 * @param md5Of md5 of that file
 */
class EditedAttachmentFinder(
    private val stampOf: (itemKey: String) -> Pair<Long, Long>?,
    private val md5Of: (itemKey: String) -> String,
    private val store: Store = Store.NONE,
) {
    data class Cached(val size: Long, val mtime: Long, val md5: String)

    /** Where hashes outlive the process (the database). Blocking. */
    interface Store {
        fun get(itemKey: String): Cached?
        fun put(itemKey: String, cached: Cached)
        fun remove(itemKey: String)

        companion object {
            val NONE = object : Store {
                override fun get(itemKey: String): Cached? = null
                override fun put(itemKey: String, cached: Cached) {}
                override fun remove(itemKey: String) {}
            }
        }
    }

    private val cache = HashMap<String, Cached>()

    /** @param bases item key to base md5, for the attachments to check */
    @Synchronized
    fun edited(bases: Map<String, String>): Set<String> =
        bases.filter { (key, base) ->
            val md5 = currentMd5(key)
            md5 != null && !md5.equals(base, ignoreCase = true)
        }.keys

    /** md5 of the file on the device now, null if there's none or it can't be read. */
    @Synchronized
    fun currentMd5(itemKey: String): String? {
        val stamp = stampOf(itemKey)
        if (stamp == null) {
            cache.remove(itemKey)
            runCatching { store.remove(itemKey) }
            return null
        }
        val (size, mtime) = stamp
        val known = cache[itemKey] ?: runCatching { store.get(itemKey) }.getOrNull()?.also { cache[itemKey] = it }
        known?.let { if (it.size == size && it.mtime == mtime) return it.md5 }
        val md5 = runCatching { md5Of(itemKey) }.getOrNull() ?: return null
        val cached = Cached(size, mtime, md5)
        cache[itemKey] = cached
        if (mtime > 0) runCatching { store.put(itemKey, cached) }
        return md5
    }
}
