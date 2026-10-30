package com.mickstarify.zooforzotero.ZoteroStorage.Downloads

import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item

/** Which of the selected items' attachments a bulk download or deletion acts on. */
enum class AttachmentKind(val label: String) {
    PDF("PDFs"),
    HTML("Web snapshots (HTML)"),
    ALL("All attachments");

    fun matches(attachment: Item): Boolean = when (this) {
        PDF -> attachment.data["contentType"] == "application/pdf"
        HTML -> attachment.data["contentType"] == "text/html"
        ALL -> true
    }

    companion object {
        /**
         * Whether [attachment] has a file stored in Zotero (or on WebDAV), which can be
         * downloaded: not a link to a file or a web page.
         */
        fun isStoredFile(attachment: Item): Boolean {
            val linkMode = attachment.data["linkMode"]
            return linkMode != "linked_file" && linkMode != "linked_url" && attachment.isDownloadable()
        }
    }
}

/** For one [AttachmentKind]: the selection's stored files [onDevice] and those [missing] from it. */
data class AttachmentCounts(val missing: List<Item>, val onDevice: List<Item>) {
    val total get() = missing.size + onDevice.size

    companion object {
        /**
         * The stored files among [attachments] for each kind, split by [isOnDevice]; kinds
         * that have none are left out.
         */
        fun of(attachments: List<Item>, isOnDevice: (Item) -> Boolean): Map<AttachmentKind, AttachmentCounts> {
            val stored = attachments.filter { AttachmentKind.isStoredFile(it) }.distinctBy { it.itemKey }
            val here = stored.filter(isOnDevice).map { it.itemKey }.toSet()
            return AttachmentKind.entries.associateWith { kind ->
                val files = stored.filter { kind.matches(it) }
                AttachmentCounts(files.filter { it.itemKey !in here }, files.filter { it.itemKey in here })
            }.filterValues { it.total > 0 }
        }
    }
}
