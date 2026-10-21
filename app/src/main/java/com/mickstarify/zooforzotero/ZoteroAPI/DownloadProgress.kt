package com.mickstarify.zooforzotero.ZoteroAPI

data class DownloadProgress(
    val progress: Long,
    val total: Long,
    val mtime: Long = -1,
    val metadataHash: String = ""
)

/**
 * Limits progress updates to a few a second. A download emitted one per buffer (every
 * 32-64 KB), each posted to the main thread: thousands for a large PDF.
 */
class ProgressThrottle(private val intervalMs: Long = 150) {
    private var last = 0L

    /** True on the first call and then at most once per interval. */
    fun due(): Boolean {
        val now = System.currentTimeMillis()
        if (last != 0L && now - last < intervalMs) return false
        last = now
        return true
    }
}
