package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.SyncSetup.domain.AuthenticationStorageImpl
import com.mickstarify.zooforzotero.ZoteroAPI.AttachmentUploader
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroAPI
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import com.mickstarify.zooforzotero.ZoteroStorage.EditedAttachments
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditStore
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.ZoteroDB
import com.mickstarify.zooforzotero.di.SingletonComponentsEntryPoint
import dagger.hilt.android.EntryPointAccessors
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection as ZoteroCollection

private const val TAG = "LibraryActivityModel"

class LibraryActivityModel(private val ui: LibraryUi, val context: Context) : Contract.Model {

    // stores the current item being viewed by the user. (useful for refreshing the view)
    var selectedItem: Item? = null
    var isDisplayingItems = false

    // Built on first use, or in the background once the library is open (finishLibrarySync):
    // OkHttp, Retrofit and Gson take a while to load, and a start without a sync needs none
    // of them before the list is up.
    private val zoteroAPI: ZoteroAPI by lazy {
        val auth = AuthenticationStorageImpl(context)
        ZoteroAPI(auth.getUserKey(), auth.getUserID(), auth.getUsername(), attachmentStorageManager, preferences)
    }

    val zoteroDatabase: ZoteroDatabase
    val attachmentStorageManager: AttachmentStorageManager
    val preferences: PreferenceManager

    val library = OpenLibrary(context)
    val navigator get() = library.navigator
    val states get() = navigator.states
    val state: LibraryModelState
        get() = navigator.current
    private val zoteroDB: ZoteroDB get() = library.db

    override fun isLoaded(): Boolean = library.isLoaded()

    val groups by lazy { LibraryGroups(ui, { zoteroAPI }, zoteroDatabase, preferences) }

    val lists: LibraryLists by lazy {
        LibraryLists(ui, library, zoteroDatabase, preferences) { deviceState.current }
    }

    private val itemEdits by lazy { ItemEditStore(zoteroDatabase) }

    // lazy: zoteroDatabase is set in init, after the properties
    private val editedAttachments by lazy {
        EditedAttachments(zoteroDatabase, attachmentStorageManager) { zoteroDB.groupID }
    }

    private val uploader by lazy {
        AttachmentUploader(context, { zoteroAPI }, attachmentStorageManager, preferences, editedAttachments) { zoteroDB }
    }

    val attachments: LibraryAttachments by lazy {
        LibraryAttachments(
            context, ui, library, { zoteroAPI }, attachmentStorageManager, preferences,
            editedAttachments, uploader, onFilesChanged = { refreshPendingEdits() }
        )
    }

    val edits: LibraryItemEdits by lazy {
        LibraryItemEdits(
            ui, library, { zoteroAPI }, zoteroDatabase, itemEdits,
            invalidateList = { lists.invalidate() },
            refreshPendingEdits = { refreshPendingEdits(it) },
            selectedItem = { selectedItem },
            syncItems = { sync.syncItems() },
        )
    }

    val sync: LibrarySync by lazy {
        LibrarySync(
            ui, library, { zoteroAPI }, zoteroDatabase, preferences, itemEdits, editedAttachments,
            lists, attachments, edits,
            refreshPendingEdits = { refreshPendingEdits() },
            selectedItem = { selectedItem },
        )
    }

    val deviceState: LibraryDeviceState by lazy {
        LibraryDeviceState(
            ui, library, editedAttachments, { attachments.findEditedAttachments() }, itemEdits,
            filterNeedsDeviceState = { lists.libraryFilter.needsDeviceState },
        )
    }

    override fun getCollections(): List<ZoteroCollection> = library.collections

    /**
     * Applies Settings → Group libraries at once. On: the groups are listed. Off: they
     * go from the sidebar, an open group closes (back to My Library) and Back forgets
     * places in groups. Groups only sync when opened, so nothing else depends on it.
     */
    fun setGroupLibrariesEnabled(enabled: Boolean) {
        if (enabled) {
            groups.loadGroups()
            return
        }
        groups.clear()
        sync.forgetOpeningGroup()
        if (navigator.leaveGroups()) {
            usePersonalLibrary()
            ui.receiveCollections(getCollections())
            ui.redisplayItems()
        }
        ui.showGroups()
    }

    /** Works out again what's on the device and what waits to sync; see LibraryDeviceState. */
    fun refreshPendingEdits(onDone: () -> Unit = {}) = deviceState.refresh(onDone)

    override fun usePersonalLibrary() {
        library.usePersonalLibrary()
    }

    /** Switches the list and the sidebar to My Library, at its top. */
    fun openMyLibrary() {
        if (state.isUsingGroup()) {
            usePersonalLibrary()
            navigator.openLibrary(null)
            ui.receiveCollections(getCollections())
        } else {
            navigator.openLibrary(null)
        }
        ui.redisplayItems()
    }

    /** Gives [item] the fields left out at startup, before it is shown in full. */
    fun completeItem(item: Item) = zoteroDB.completeItem(item)

    fun getCurrentCollection(): String {
        return navigator.currentCollection()
    }

    /** Opens a collection (or Unfiled, Trash, …) in the library that's open. */
    fun setCurrentCollection(collectionName: String) {
        navigator.setCollection(collectionName)
    }

    fun checkAttachmentStorageAccess() {
        try {
            if (attachmentStorageManager.testStorage() == false) {
                throw Exception()
            }
        } catch (e: Exception) {
            Log.e("zotero", "error testing storage. ${e}")
            ui.createErrorAlert(
                "Can't use the attachment folder",
                "The app can't access the folder set in Settings → Attachment folder, so " +
                        "it's back to the default. Choose the folder again in Settings.",
                {})
            preferences.useExternalCache()
        }
    }

    fun getCollectionFromKey(collectionKey: String): ZoteroCollection? = library.collection(collectionKey)

    fun isUsingGroups(): Boolean {
        return navigator.isUsingGroup()
    }

    fun getCurrentGroup(): GroupInfo? {
        return navigator.currentGroup()
    }

    private var doubleBackToExitPressedOnce = false
    override fun loadPriorState() {
        // check to see if we're at the root level, the first state is a junk state, so we
        // check at 2.
        if (states.size <= 2) {
            if (doubleBackToExitPressedOnce) {
                ui.finishLibrary()
                return
            }

            this.doubleBackToExitPressedOnce = true
            ui.makeToastAlert("Press Back again to exit.")
            Handler(Looper.getMainLooper()).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
            return
        }
        val oldState = states.peek()
        states.pop()
        if (oldState.currentGroup.id != state.currentGroup.id) {
            // back into another library; its database was loaded when we were there
            if (state.isUsingGroup()) {
                library.useGroup(state.currentGroup.id)
            } else {
                this.usePersonalLibrary()
            }
            ui.receiveCollections(getCollections())
            ui.redisplayItems()
        } else if (oldState.currentCollection != state.currentCollection) {
            ui.redisplayItems()
        } else {
            Log.e("zotero", "error unable to determine state differences!!")
        }
    }

    init {
        val appContext = context.applicationContext
        val hiltEntryPoint = EntryPointAccessors.fromApplication(appContext, SingletonComponentsEntryPoint::class.java)

        zoteroDatabase = hiltEntryPoint.getZoteroDatabase()
        attachmentStorageManager = hiltEntryPoint.getAttachmentStorageManager()
        preferences = hiltEntryPoint.getPreferences()

        val auth = AuthenticationStorageImpl(context)
        // the initial (root) library state is seeded by LibraryNavigator's init.

        if (!auth.hasCredentials()) {
            ui.createErrorAlert(
                "Sign in again",
                "The stored Zotero login is no longer valid."
            ) { ui.finishLibrary() }
            auth.destroyCredentials()
            zoteroDB.clearItemsVersion()
        }

        checkAttachmentStorageAccess()
    }


}