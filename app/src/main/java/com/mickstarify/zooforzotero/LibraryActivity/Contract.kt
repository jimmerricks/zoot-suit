package com.mickstarify.zooforzotero.LibraryActivity

import com.mickstarify.zooforzotero.LibraryActivity.ViewModels.LibraryListViewModel
import com.mickstarify.zooforzotero.LibraryActivity.ViewModels.LibraryLoadingScreenViewModel
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item

interface Contract {
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
}
