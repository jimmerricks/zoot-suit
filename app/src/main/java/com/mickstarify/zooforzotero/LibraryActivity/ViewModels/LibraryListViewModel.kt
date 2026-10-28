package com.mickstarify.zooforzotero.LibraryActivity.ViewModels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.mickstarify.zooforzotero.LibraryActivity.EmptyListMessage
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item

/** Attachment keys whose file is on this device, and those of them with edits to sync. */
data class AttachmentStates(val onDevice: Set<String>, val edited: Set<String>)

class LibraryListViewModel : ViewModel() {
    private val items = MutableLiveData<List<Item>>()

    fun getItems(): LiveData<List<Item>> = items
    fun setItems(items: List<Item>) {
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

    // an event for the presenter, which opens the item's dialog; a redraw goes through itemChanged
    private val itemChanged = MutableLiveData<Item>()

    /** The open item changed on this device (a tag edit): its dialog redraws. Not a click. */
    fun getOnItemChanged(): LiveData<Item> = itemChanged
    fun onItemChanged(item: Item) {
        itemChanged.value = item
    }

    private val attachmentClicked = MutableLiveData<Item>()
    fun getOnAttachmentClicked(): LiveData<Item> = attachmentClicked
    fun onAttachmentClicked(attachment: Item) {
        this.attachmentClicked.value = attachment
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
