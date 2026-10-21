package com.mickstarify.zooforzotero.LibraryActivity.ViewModels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.mickstarify.zooforzotero.LibraryActivity.EmptyListMessage
import com.mickstarify.zooforzotero.LibraryActivity.ListEntry
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item

/** Attachment keys whose file is on this device, and those of them with edits to sync. */
data class AttachmentStates(val onDevice: Set<String>, val edited: Set<String>)

class LibraryListViewModel : ViewModel() {
    private val items = MutableLiveData<List<ListEntry>>()

    fun getItems(): LiveData<List<ListEntry>> = items
    fun setItems(items: List<ListEntry>) {
        Log.d("zotero", "Setting items ${items.size}")
        this.items.value = items
    }

    private val attachmentStates = MutableLiveData(AttachmentStates(emptySet(), emptySet()))
    fun getAttachmentStates(): LiveData<AttachmentStates> = attachmentStates
    fun setAttachmentStates(states: AttachmentStates) {
        if (attachmentStates.value != states) attachmentStates.value = states
    }

    private val itemClicked = MutableLiveData<Item>()
    fun getOnItemClicked(): LiveData<Item> = itemClicked
    fun onItemClicked(item: Item) {
        this.itemClicked.value = item
    }

    private val attachmentClicked = MutableLiveData<Item>()
    fun getOnAttachmentClicked(): LiveData<Item> = attachmentClicked
    fun onAttachmentClicked(attachment: Item) {
        this.attachmentClicked.value = attachment
    }

    private val collectionClicked = MutableLiveData<Collection>()
    fun getOnCollectionClicked(): LiveData<Collection> = collectionClicked
    fun onCollectionClicked(collection: Collection) {
        this.collectionClicked.value = collection
    }

    private val isShowingLoadingAnimation = MutableLiveData<Boolean>(false)
    fun setIsShowingLoadingAnimation(value: Boolean) {
        if (isShowingLoadingAnimation.value != value) {
            isShowingLoadingAnimation.value = value
        }
    }

    fun getIsShowingLoadingAnimation(): LiveData<Boolean> = isShowingLoadingAnimation

    private val onLibraryRefreshRequested = MutableLiveData<Int>()
    fun onLibraryRefreshRequested() {
        // changes the value so any listener will get pinged.
        onLibraryRefreshRequested.value = (onLibraryRefreshRequested.value ?: 0) + 1
    }

    fun getOnLibraryRefreshRequested(): LiveData<Int> = onLibraryRefreshRequested

    private val libraryFilterText = MutableLiveData<String>("")
    fun getLibraryFilterText(): LiveData<String> = libraryFilterText
    fun setLibraryFilterText(query: String) {
        if (this.libraryFilterText.value != query) {
            this.libraryFilterText.value = query
        }
    }

    /** What the list says when it shows nothing; set with every list. */
    private val emptyMessage = MutableLiveData<EmptyListMessage>()
    fun getEmptyMessage(): LiveData<EmptyListMessage> = emptyMessage
    fun setEmptyMessage(message: EmptyListMessage) {
        if (emptyMessage.value != message) emptyMessage.value = message
    }

    private val onClearFilterRequested = MutableLiveData<Int>()
    fun getOnClearFilterRequested(): LiveData<Int> = onClearFilterRequested
    fun onClearFilterRequested() {
        onClearFilterRequested.value = (onClearFilterRequested.value ?: 0) + 1
    }
}
