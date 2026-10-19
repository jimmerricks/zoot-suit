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
 *
 * @param stampOf size and mtime of the file for an item key, null if it isn't on the device
 * @param md5Of md5 of that file
 */
class EditedAttachmentFinder(
    private val stampOf: (itemKey: String) -> Pair<Long, Long>?,
    private val md5Of: (itemKey: String) -> String,
) {
    private data class Cached(val size: Long, val mtime: Long, val md5: String)

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
            return null
        }
        val (size, mtime) = stamp
        cache[itemKey]?.let { if (it.size == size && it.mtime == mtime) return it.md5 }
        val md5 = runCatching { md5Of(itemKey) }.getOrNull() ?: return null
        cache[itemKey] = Cached(size, mtime, md5)
        return md5
    }
}
