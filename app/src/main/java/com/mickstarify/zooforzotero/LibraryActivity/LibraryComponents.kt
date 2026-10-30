package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.SyncSetup.domain.AuthenticationStorageImpl
import com.mickstarify.zooforzotero.ZoteroAPI.AttachmentUploader
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroAPI
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import com.mickstarify.zooforzotero.ZoteroStorage.EditedAttachments
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditStore
import com.mickstarify.zooforzotero.di.SingletonComponentsEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * The parts behind the library screen, made once and wired together: the open library,
 * its lists, sync, attachments, item edits, device state and groups. Each reports to the
 * screen through [ui]. Made by the screen's logic, which outlives activities.
 *
 * @param selectedItem the item open in the item view, if any
 */
class LibraryComponents(
    private val ui: LibraryUi,
    private val context: Context,
    private val selectedItem: () -> Item?,
) {
    private val hilt = EntryPointAccessors.fromApplication(
        context.applicationContext, SingletonComponentsEntryPoint::class.java
    )
    val zoteroDatabase: ZoteroDatabase = hilt.getZoteroDatabase()
    val attachmentStorageManager: AttachmentStorageManager = hilt.getAttachmentStorageManager()
    val preferences: PreferenceManager = hilt.getPreferences()

    // Built on first use, or in the background once the library is open (finishLibrarySync):
    // OkHttp, Retrofit and Gson take a while to load, and a start without a sync needs none
    // of them before the list is up.
    private val zoteroAPI: ZoteroAPI by lazy {
        val auth = AuthenticationStorageImpl(context)
        ZoteroAPI(auth.getUserKey(), auth.getUserID(), auth.getUsername(), attachmentStorageManager, preferences)
    }

    val library = OpenLibrary(context)

    val groups by lazy { LibraryGroups(ui, { zoteroAPI }, zoteroDatabase, preferences) }

    val lists: LibraryLists by lazy {
        LibraryLists(ui, library, zoteroDatabase, preferences) { deviceState.current }
    }

    private val itemEdits by lazy { ItemEditStore(zoteroDatabase) }

    private val editedAttachments by lazy {
        EditedAttachments(zoteroDatabase, attachmentStorageManager) { library.db.groupID }
    }

    private val uploader by lazy {
        AttachmentUploader(context, { zoteroAPI }, attachmentStorageManager, preferences, editedAttachments) { library.db }
    }

    val attachments: LibraryAttachments by lazy {
        LibraryAttachments(
            context, ui, library, { zoteroAPI }, attachmentStorageManager, preferences,
            editedAttachments, uploader, zoteroDatabase,
            onDevice = { deviceState.current.onDevice },
            onFilesChanged = { deviceState.refresh() },
        )
    }

    val edits: LibraryItemEdits by lazy {
        LibraryItemEdits(
            ui, library, { zoteroAPI }, zoteroDatabase, itemEdits,
            invalidateList = { lists.invalidate() },
            refreshPendingEdits = { deviceState.refresh(it) },
            selectedItem = selectedItem,
            syncItems = { sync.syncItems() },
        )
    }

    val sync: LibrarySync by lazy {
        LibrarySync(
            ui, library, { zoteroAPI }, zoteroDatabase, preferences, itemEdits, editedAttachments,
            lists, attachments, edits,
            refreshPendingEdits = { deviceState.refresh() },
            selectedItem = selectedItem,
        )
    }

    val deviceState: LibraryDeviceState by lazy {
        LibraryDeviceState(
            ui, library, editedAttachments, { attachments.findEditedAttachments() }, itemEdits,
            filterNeedsDeviceState = { lists.libraryFilter.needsDeviceState },
        )
    }
}
