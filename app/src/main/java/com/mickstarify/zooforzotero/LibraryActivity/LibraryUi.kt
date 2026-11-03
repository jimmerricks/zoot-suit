package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Intent
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.SyncStatus
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
    /**
     * Starts [intent] from the screen; [onNoApp] if no app takes it. The screen may start
     * it later (queued while the app is in the background), so a caller's own try/catch
     * wouldn't see that.
     */
    fun startActivity(intent: Intent, onNoApp: () -> Unit = {})

    // attachment progress
    fun updateAttachmentDownloadProgress(progress: Long, total: Long)
    fun finishDownloadingAttachment()
    fun attachmentDownloadError(message: String = "")
    fun startUploadingAttachmentProgress(attachment: Item, position: Int, total: Int)
    fun stopUploadingAttachmentProgress()

    // syncing
    /** The library is open: the list in place of the loading screen. */
    fun showLibraryList()
    /** What a sync does now (SyncStatus); also its last state, once it ended. */
    fun showSyncStatus(status: SyncStatus)

    // what the screen shows
    fun receiveCollections(collections: List<Collection>)
    fun setCollection(collectionName: String)
    fun redisplayItems()
    /** The open item's dialog draws its item again, in place (a note added or deleted). */
    fun refreshItemView()
    /** [item] changed on this device (a tag edit): redraws its dialog and lists that depend on it. */
    fun showEditedItem(item: Item)
    /**
     * Items changed on this device in bulk (a tag on the selection): redraws lists that
     * depend on them; any list if [collectionsChanged] (it may be the collection, or Unfiled).
     */
    fun showEditedItems(collectionsChanged: Boolean = false)
    fun showGroups()
    fun showPendingEdits(count: Int)
    fun showAttachmentStates(onDevice: Set<String>, edited: Set<String>)
    fun finishLibrary()
}
