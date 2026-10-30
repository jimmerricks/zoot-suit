package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentLocalState

/**
 * The actions a long press on an attachment offers: only those that do something for the
 * file as it stands, rather than all of them always ("Download" for a file already here,
 * "Upload changes" with nothing changed).
 */
object AttachmentMenu {
    enum class Action(val label: String) {
        OPEN("Open"),
        DOWNLOAD("Download"),
        /** Fetches Zotero's version if it moved on since the copy here; else says it hasn't. */
        CHECK_NEWER("Check for a newer version"),
        DOWNLOAD_NEWER("Download the newer version"),
        /** In a conflict: replaces the edits here, after asking (downloadAttachmentOnly). */
        DOWNLOAD_ZOTEROS("Download Zotero's version"),
        UPLOAD("Upload changes"),
        DELETE("Remove from this device"),
    }

    /**
     * The actions for an attachment in [state]. [linkMode] "linked_file" / "linked_url":
     * a file elsewhere or a web page, which can only be opened. [canUpload]: My Library
     * with uploading on; [canCheck]: the server can be asked for Zotero's version (My
     * Library).
     */
    fun actions(state: AttachmentLocalState, linkMode: String?, canUpload: Boolean, canCheck: Boolean): List<Action> {
        if (linkMode == "linked_file" || linkMode == "linked_url") return listOf(Action.OPEN)
        return when (state) {
            AttachmentLocalState.NOT_DOWNLOADED -> listOf(Action.OPEN, Action.DOWNLOAD)
            AttachmentLocalState.ON_DEVICE ->
                listOfNotNull(Action.OPEN, Action.CHECK_NEWER.takeIf { canCheck }, Action.DELETE)
            AttachmentLocalState.OUTDATED -> listOf(Action.OPEN, Action.DOWNLOAD_NEWER, Action.DELETE)
            AttachmentLocalState.EDITED ->
                listOfNotNull(Action.OPEN, Action.UPLOAD.takeIf { canUpload }, Action.DELETE)
            // the upload would be refused: the file changed in Zotero too
            AttachmentLocalState.CONFLICT -> listOf(Action.OPEN, Action.DOWNLOAD_ZOTEROS, Action.DELETE)
        }
    }
}
