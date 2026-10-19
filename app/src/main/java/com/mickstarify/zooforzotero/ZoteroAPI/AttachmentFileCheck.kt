package com.mickstarify.zooforzotero.ZoteroAPI

/**
 * Last check before an edited attachment leaves the device. A viewer that crashed
 * or saved badly can leave an empty or truncated file, and an upload replaces the
 * only remote copy. Only the first and last [WINDOW] bytes are needed, so large
 * PDFs aren't read into memory.
 */
object AttachmentFileCheck {
    const val WINDOW = 1024

    /** @return null if the file looks uploadable, otherwise why not */
    fun problem(head: ByteArray, tail: ByteArray, size: Long, isPdf: Boolean): String? {
        if (size <= 0L) return "the file is empty"
        if (!isPdf) return null
        // the spec allows the header anywhere in the first 1024 bytes
        if (!contains(head, "%PDF-")) return "the file doesn't start like a PDF"
        // some writers append bytes after %%EOF, so look across the whole tail window
        if (!contains(tail, "%%EOF")) return "the PDF looks truncated (no %%EOF at the end)"
        return null
    }

    private fun contains(bytes: ByteArray, marker: String): Boolean {
        val m = marker.toByteArray(Charsets.US_ASCII)
        if (bytes.size < m.size) return false
        outer@ for (i in 0..bytes.size - m.size) {
            for (j in m.indices) if (bytes[i + j] != m[j]) continue@outer
            return true
        }
        return false
    }
}
