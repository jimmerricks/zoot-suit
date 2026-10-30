package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Intent
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item

/** What the library screen's activity shows on the logic's behalf; see ScreenCommands. */
interface LibraryScreen {
    fun makeToastAlert(message: String)
    fun createErrorAlert(title: String, message: String, onClick: () -> Unit)
    fun createYesNoPrompt(
        title: String, message: String, yesText: String, noText: String, onYesClick: () -> Unit,
        onNoClick: () -> Unit
    )
    fun showLoadingAlertDialog(message: String)
    fun hideLoadingAlertDialog()
    fun updateAttachmentDownloadProgress(progress: Int, total: Int)
    fun hideAttachmentDownloadProgress()
    fun showAttachmentUploadProgress(attachment: Item, position: Int, total: Int)
    fun hideAttachmentUploadProgress()
    fun showAttachmentStates(onDevice: Set<String>, edited: Set<String>)
    fun showItemDialog()
    fun closeItemView()
    fun showNote(note: Note)
    fun startActivity(intent: Intent)
    fun finish()
}
