package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Intent
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item

/**
 * What the library screen's logic (sync, attachments, edits, lists) asks of the screen:
 * messages, progress and redraws. Kept to these calls so that the parts behind the screen
 * don't depend on the activity or on each other. Call on the main thread.
 */
interface LibraryUi {
    // messages
    fun makeToastAlert(message: String)
    fun createErrorAlert(title: String, message: String, onClick: () -> Unit)
    fun createYesNoPrompt(
        title: String, message: String, yesText: String, noText: String, onYesClick: () -> Unit,
        onNoClick: () -> Unit
    )
    fun showLoadingAlertDialog(message: String)
    fun hideLoadingAlertDialog()
    /** Edited attachments to upload, all ticked; [onUpload] gets the ticked indices. */
    fun chooseAttachmentsToUpload(labels: List<String>, onUpload: (List<Int>) -> Unit)
    fun startActivity(intent: Intent)

    // attachment progress
    fun updateAttachmentDownloadProgress(progress: Long, total: Long)
    fun finishDownloadingAttachment()
    fun attachmentDownloadError(message: String = "")
    fun startUploadingAttachmentProgress(attachment: Item, position: Int, total: Int)
    fun stopUploadingAttachmentProgress()

    // syncing
    fun showLibraryLoadingAnimation()
    fun hideLibraryLoadingAnimation()
    fun showBasicSyncAnimation()
    fun hideBasicSyncAnimation()
    fun updateLibraryRefreshProgress(progress: Int, total: Int, message: String)

    // what the screen shows
    fun receiveCollections(collections: List<Collection>)
    fun setCollection(collectionName: String)
    fun redisplayItems()
    fun refreshItemView()
    /** [item] changed on this device (a tag edit): redraws its dialog and lists that depend on it. */
    fun showEditedItem(item: Item)
    /** Items changed on this device in bulk (a tag on the selection): redraws lists that depend on them. */
    fun showEditedItems()
    fun showGroups()
    fun showPendingEdits(count: Int)
    fun showAttachmentStates(onDevice: Set<String>, edited: Set<String>)
    fun finishLibrary()
}
