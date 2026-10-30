package com.mickstarify.zooforzotero.LibraryActivity.ViewModels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class LibraryLoadingScreenViewModel : ViewModel() {
    private val loadingMessage = MutableLiveData<String>()

    fun getLoadingMessage(): LiveData<String> = loadingMessage
    fun setLoadingMessage(message: String) {
        loadingMessage.value = message
    }
}