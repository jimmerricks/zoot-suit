package com.mickstarify.zooforzotero.ZoteroStorage

/**
 * What to do with a local attachment copy when it's opened, given:
 * - base: md5 of the file as this device last downloaded/uploaded it (AttachmentBase),
 *   null for copies downloaded before that was recorded
 * - zoteroMd5: the attachment's md5 in Zotero as of the last sync ("" if unknown)
 * - localMd5: md5 of the file on the device now
 *
 * If Zotero's md5 moved on from the base, the local copy is stale: someone changed the
 * file elsewhere. Opening it silently would let annotations be made on an old version.
 */
object AttachmentOpenCheck {
    enum class Action {
        /** open the local copy as it is */
        OPEN,
        /** local copy is exactly Zotero's version; record that as the base, then open */
        RECORD_BASE_AND_OPEN,
        /** stale and unedited: replace it with Zotero's version */
        REDOWNLOAD,
        /** stale but edited here: ask before replacing local changes */
        ASK_EDITED_STALE
    }

    fun decide(base: String?, zoteroMd5: String, localMd5: String): Action {
        val zotero = zoteroMd5.lowercase()
        val local = localMd5.lowercase()
        if (zotero.isEmpty()) return Action.OPEN // nothing to compare against
        val b = base?.lowercase()
        if (b == null) {
            // copy from before bases were recorded: adopt it only if provably current
            return if (local == zotero) Action.RECORD_BASE_AND_OPEN else Action.OPEN
        }
        if (b == zotero) return Action.OPEN // based on the current version, edited or not
        return when (local) {
            zotero -> Action.RECORD_BASE_AND_OPEN // already matches Zotero again
            b -> Action.REDOWNLOAD
            else -> Action.ASK_EDITED_STALE
        }
    }
}
