package com.mickstarify.zooforzotero.LibraryActivity

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/**
 * Keeps the library screen's presenter, and with it the loaded library and the
 * navigation stack, across activity recreation (rotation, other configuration
 * changes). Before, every rotation built a new presenter and model: the library was
 * reloaded from the database, the open group and Back were lost, and the old model's
 * callbacks kept drawing into the destroyed activity.
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    val presenter = LibraryActivityPresenter(application)

    override fun onCleared() {
        presenter.onCleared()
    }
}
