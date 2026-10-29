package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.LibraryActivity.ViewModels.LibraryListViewModel
import com.mickstarify.zooforzotero.LibraryActivity.ViewModels.LibraryLoadingScreenViewModel
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item

interface Contract {
    interface View {
        fun initUI()
        fun createErrorAlert(title: String, message: String, onClick: () -> Unit)

        //        fun showLoadingAnimation(showScreen: Boolean)
        fun updateLibraryLoadingProgress(
            progress: Int,
            total: Int = -1,
            message: String
        )

        //        fun hideLoadingAnimation()
        fun setTitle(title: String)
        fun showItemDialog()
        fun updateAttachmentDownloadProgress(progress: Int, total: Int)
        fun hideAttachmentDownloadProgress()
        fun makeToastAlert(message: String)
        fun showLibraryContentDisplay(message: String = "")
        fun hideLibraryContentDisplay()
        fun closeItemView()
        fun showAttachmentUploadProgress(attachment: Item, position: Int, total: Int)
        fun hideAttachmentUploadProgress()
        fun createYesNoPrompt(
            title: String, message: String, yesText: String, noText: String, onYesClick: () -> Unit,
            onNoClick: () -> Unit
        )

        fun chooseAttachmentsToUpload(labels: List<String>, onUpload: (List<Int>) -> Unit)
        fun showPendingEdits(count: Int)
        fun showAttachmentStates(onDevice: Set<String>, edited: Set<String>)
        fun showNote(note: Note)
        fun showSidebar(rows: List<SidebarRow>)
        fun showLoadingAlertDialog(message: String)
        fun hideLoadingAlertDialog()
    }

    interface Presenter : LibraryUi {
        fun selectItem(item: Item, longPress: Boolean = false)
        fun requestLibraryRefresh()
        fun openAttachment(item: Item)
        fun isShowingContent(): Boolean
        fun cancelAttachmentDownload()
        fun modifyNote(note: Note)
        fun deleteNote(note: Note)
        fun openLibrary(groupId: Int)
        fun onResume()
        fun openTrash()
        fun uploadAttachment(item: Item)
        fun uploadEditedAttachments()
        fun requestForceResync()
        fun backButtonPressed()
        fun openMyPublications()
        /** The open library's tags with their item counts, read off the main thread; [onResult] runs on it. */
        fun loadTags(onResult: (List<Pair<String, Int>>) -> Unit)

        var libraryListViewModel: LibraryListViewModel
        var libraryLoadingViewModel: LibraryLoadingScreenViewModel
    }

    interface Model {
        fun downloadLibrary(doRefresh: Boolean = false, useSmallLoadingAnimation: Boolean = false)
        fun refreshLibrary(useSmallLoadingAnimation: Boolean = false)
        fun getCollections(): List<Collection>
        fun isLoaded(): Boolean
        fun startGroupSync(group: GroupInfo, refresh: Boolean = false)
        fun usePersonalLibrary()
        fun loadPriorState()
        fun isFirstSync(): Boolean
    }
}
