package com.mickstarify.zooforzotero.ZoteroStorage

/**
 * Where an attachment's file stands on this device, for showing next to it. Same inputs
 * as AttachmentOpenCheck: base (md5 this device last downloaded/uploaded, null if never
 * recorded), Zotero's md5 as of the last sync, and the md5 of the local file.
 */
enum class AttachmentLocalState(val label: String) {
    NOT_DOWNLOADED("Not on this device"),
    ON_DEVICE("On this device"),
    EDITED("Edited here · uploads at the next sync"),
    OUTDATED("On this device · a newer version is in Zotero"),
    CONFLICT("Edited here, and changed elsewhere too · won't be uploaded");

    companion object {
        fun of(localMd5: String?, base: String?, zoteroMd5: String): AttachmentLocalState {
            val local = localMd5?.lowercase() ?: return NOT_DOWNLOADED
            val b = base?.lowercase() ?: return ON_DEVICE // can't judge copies without a base
            val zotero = zoteroMd5.lowercase()
            val changedElsewhere = zotero.isNotEmpty() && zotero != b
            val edited = local != b
            return when {
                changedElsewhere && local == zotero -> ON_DEVICE
                changedElsewhere && edited -> CONFLICT
                changedElsewhere -> OUTDATED
                edited -> EDITED
                else -> ON_DEVICE
            }
        }
    }
}
