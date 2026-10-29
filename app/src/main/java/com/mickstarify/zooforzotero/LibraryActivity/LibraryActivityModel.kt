package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.SyncSetup.domain.AuthenticationStorageImpl
import io.reactivex.rxjava3.core.Single
import com.mickstarify.zooforzotero.ZoteroAPI.AttachmentUploader
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.OnSyncChangeListener
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.SyncManager
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
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection as ZoteroCollection

private const val TAG = "LibraryActivityModel"

class LibraryActivityModel(private val ui: LibraryUi, val context: Context) :
    Contract.Model, OnSyncChangeListener {

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

    private val syncManager: SyncManager by lazy { SyncManager(zoteroAPI, preferences, zoteroDatabase, this) }

    val zoteroDatabase: ZoteroDatabase
    val attachmentStorageManager: AttachmentStorageManager
    val preferences: PreferenceManager

    val library = OpenLibrary(context)
    val navigator get() = library.navigator
    val states get() = navigator.states
    val state: LibraryModelState
        get() = navigator.current
    private val zoteroDB: ZoteroDB get() = library.db

    private var performedCleanSync: Boolean = false

    /**
     * A sync sends this device's edits first, then fetches the library's changes. Edits
     * that can't go up (offline, refused) stay on the device and are tried again at the
     * next sync; the download half runs either way.
     */
    override fun refreshLibrary(useSmallLoadingAnimation: Boolean) {
        if (!state.isUsingGroup()) {
            val pull = {
                downloadLibrary(doRefresh = true, useSmallLoadingAnimation = useSmallLoadingAnimation)
            }
            val attachmentsThenPull: () -> Unit = {
                if (attachments.isUploading()) {
                    pull()
                } else {
                    Single.fromCallable { attachments.findEditedAttachments() }
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe({ edited ->
                            if (edited.isEmpty()) pull() else attachments.uploadAttachments(edited, onDone = pull)
                        }, { e ->
                            Log.e("zotero", "looking for edited attachments failed: $e")
                            pull()
                        })
                }
            }
            edits.uploadItemEdits(onDone = attachmentsThenPull)
        } else {
            this.startGroupSync(state.currentGroup, refresh = true)
        }
    }

    /** Whether [db] (by default the open library) was last synced a day or more ago. */
    fun shouldIUpdateLibrary(db: ZoteroDB = zoteroDB): Boolean {
        val currentTimestamp = System.currentTimeMillis()
        val lastModified = db.getLastSyncedTimestamp()
        Log.d("zotero", "last modified = $lastModified")

        if (TimeUnit.MILLISECONDS.toHours(currentTimestamp - lastModified) >= 24) {
            return true
        }
        return false
    }

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
            syncItems = { syncManager.startItemsSync(db = zoteroDB) },
        )
    }

    val deviceState: LibraryDeviceState by lazy {
        LibraryDeviceState(
            ui, library, editedAttachments, { attachments.findEditedAttachments() }, itemEdits,
            filterNeedsDeviceState = { lists.libraryFilter.needsDeviceState },
        )
    }

    override fun downloadLibrary(doRefresh: Boolean, useSmallLoadingAnimation: Boolean) {
        if (!doRefresh && zoteroDB.isOpen()) {
            Log.d("zotero", "not resyncing library, already have a copy.")
            return
        }
        if (zoteroDB.getLibraryVersion() <= 0) {
            this.performedCleanSync = true
        }
        syncManager.startCompleteSync(zoteroDB, useSmallLoadingAnimation)
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
        openingGroup = null
        openingGroupCollection = null
        if (navigator.leaveGroups()) {
            usePersonalLibrary()
            ui.receiveCollections(getCollections())
            ui.redisplayItems()
        }
        ui.showGroups()
    }

    /** Works out again what's on the device and what waits to sync; see LibraryDeviceState. */
    fun refreshPendingEdits(onDone: () -> Unit = {}) = deviceState.refresh(onDone)

    // the group the user asked to open; its load switches the list to it (see finishLibrarySync)
    private var openingGroup: GroupInfo? = null
    // a collection to open in that group once it's loaded (restoring the place at startup)
    private var openingGroupCollection: String? = null

    /**
     * Opens a group, or refreshes the open one ([refresh]). A group synced before opens
     * from the device, offline too and while My Library syncs; one last synced a day or
     * more ago is then refreshed in the background, as My Library is at startup. Only a
     * group never synced here waits for the network.
     */
    override fun startGroupSync(group: GroupInfo, refresh: Boolean) {
        val db = library.groups.getGroup(group.id)
        if (!refresh) openingGroup = group
        if (!refresh && (db.isOpen() || db.getLastSyncedTimestamp() != 0L)) {
            finishLibrarySync(db) {
                if (shouldIUpdateLibrary(db) && !syncManager.isSyncing()) {
                    syncManager.startCompleteSync(db, true)
                }
            }
            return
        }

        if (syncManager.isSyncing()) {
            Log.e("zotero", "cannot sync groups, still in a sync")
            ui.makeToastAlert("Still syncing. Open the group when the sync finishes.")
            openingGroup = null
            return
        }
        syncManager.startCompleteSync(db, refresh)
    }

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

    fun isSyncing(): Boolean = syncManager.isSyncing()

    /**
     * Continues an item download that stopped partway (offline, or cut off by Doze while
     * the display slept) from the page it reached, and returns true; false if there is
     * none or a sync is running. Downloads only: the sync that stopped sent this device's
     * edits when it started.
     */
    fun resumeInterruptedSync(): Boolean {
        if (!isLoaded() || syncManager.isSyncing() || zoteroDB.getDownloadProgress() == null) return false
        Log.d(TAG, "resuming the interrupted item download for ${zoteroDB.groupID}")
        downloadLibrary(doRefresh = true, useSmallLoadingAnimation = false)
        return true
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

    fun destroyLibrary() {
        zoteroDB.clearItemsVersion()
        zoteroDatabase.deleteEverything()
    }

    fun isUsingGroups(): Boolean {
        return navigator.isUsingGroup()
    }

    fun getCurrentGroup(): GroupInfo? {
        return navigator.currentGroup()
    }

    fun loadLibraryLocally(onFinish: () -> Unit = {}) {
        if (!zoteroDB.isOpen())
            finishLibrarySync(zoteroDB) {
                onFinish()
            }
    }

    override fun isFirstSync(): Boolean {
        return zoteroDB.getLastSyncedTimestamp() == 0L
    }

    /**
     * Opens library [db] after a sync, at startup or when a group is opened: loads its
     * collections and attachment records and shows it. Its items stay in the database;
     * the list reads what it shows from there (RoomItemSource). Then the upkeep that
     * needs no screen: copies downloaded before AttachmentBase existed get a base, a sync
     * that left the library empty is redone in full next time, and the attachment states
     * are refreshed. [onFinish] runs after all of it. Called by the SyncManager too.
     */
    override fun finishLibrarySync(db: ZoteroDB, onFinish: () -> Unit) {
        Log.d(TAG, "finishLibrarySync() called - opening library ${db.groupID}")
        val start = System.nanoTime()

        val loadCollections = db.loadCollectionsFromDatabase().doOnError { e ->
            Log.e(TAG, "loading collections from db got error $e")
            db.collections = LinkedList()
        }.onErrorComplete()

        // a sync wrote Zotero's copies of edited items; edits not uploaded yet go back on
        val reapplyEdits = Completable.fromAction {
            if (db.groupID == GroupInfo.NO_GROUP_ID) itemEdits.reapply(db.groupID)
        }.doOnError { Log.e(TAG, "reapplying item edits failed: $it") }.onErrorComplete()

        // before any sync can change Zotero's md5s (startup opens the library first)
        val upkeep = Completable.fromAction {
            runCatching { zoteroAPI } // built here rather than on the main thread; see its declaration
            try {
                editedAttachments.adoptBases(db.groupID)
            } catch (e: Exception) {
                Log.e(TAG, "adopting attachment bases failed: $e")
            }
            try {
                // once, after the upgrades that added them; lists sort and search in SQL from then on
                zoteroDatabase.fillMissingKeys(db.groupID)
            } catch (e: Exception) {
                Log.e(TAG, "filling in sort keys failed: $e")
            }
            try {
                zoteroDatabase.reclaimSpace()
            } catch (e: Exception) {
                Log.e(TAG, "reclaiming database space failed: $e")
            }
            if (zoteroDatabase.countItems(db.groupID) == 0) {
                // in case a sync failed partway, don't leave the library empty: the next one starts over
                db.setItemsVersion(0)
            }
        }.subscribeOn(Schedulers.io())

        reapplyEdits.andThen(loadCollections)
            .andThen(db.loadAttachmentInfoFromDatabase())
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnComplete {
                Log.d(TAG, "opened library ${db.groupID} in ${(System.nanoTime() - start) / 1_000_000} ms")
                lists.invalidate() // the sync may have changed anything
                ui.hideBasicSyncAnimation()
                showOpenedLibrary(db)
            }
            .andThen(upkeep)
            .observeOn(AndroidSchedulers.mainThread())
            .doOnComplete { refreshPendingEdits() }
            .onErrorComplete {
                Log.e(TAG, "opening library ${db.groupID} failed: $it")
                ui.createErrorAlert("Couldn't load the library", "Details: ${it.message ?: it}", {})
                ui.hideBasicSyncAnimation()
                true
            }
            .doAfterTerminate {
                onFinish()
            }.subscribe()
    }

    /**
     * While a first sync is still fetching the open library's attachments and notes, gets
     * [item]'s own in one request and, if any are new, shows them in its row and in the
     * dialog just opened for it. Failing (offline), the sync brings them later.
     */
    fun fetchChildrenWhileSyncing(item: Item) {
        val db = zoteroDB
        if (!syncManager.isLoadingChildren(db.groupID)) return
        fun childKeys(i: Item) = i.attachments.map { it.itemKey }.toSet() + i.notes.map { it.key }.toSet()
        val before = childKeys(item)
        zoteroAPI.getChildItems(item.itemKey, db.groupID)
            .flatMapCompletable { zoteroDatabase.writeItemPOJOs(db.groupID, it) }
            .andThen(Maybe.fromCallable { zoteroDatabase.loadItemsByKeys(listOf(item.itemKey)).firstOrNull() })
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ fresh ->
                if (childKeys(fresh) == before) return@subscribe
                item.attachments = fresh.attachments
                item.notes = fresh.notes
                ui.showEditedItem(item)
                if (selectedItem === item) ui.refreshItemView()
            }, { Log.d(TAG, "fetching the children of ${item.itemKey} failed: $it") })
    }

    override fun showNewItems(db: ZoteroDB) {
        if (db.groupID != zoteroDB.groupID || !isLoaded()) return
        lists.invalidate()
        ui.redisplayItems()
    }

    /** Shows library [db], just opened: My Library, the group being opened, or a refresh of the open one. */
    private fun showOpenedLibrary(db: ZoteroDB) {
        if (db.groupID == GroupInfo.NO_GROUP_ID) {
            ui.receiveCollections(getCollections())
            if (state.currentCollection == "unset") {
                val place = preferences.getLibraryPlace() // before "all" overwrites it
                ui.setCollection("all")
                place?.let { restorePlace(it) }
            } else {
                ui.redisplayItems()
            }
        } else if (openingGroup?.id == db.groupID || state.currentGroup.id == db.groupID) {
            library.useGroup(db.groupID)
            // opening the group; a refresh of the open group keeps its place
            val opening = openingGroup
            if (opening != null && state.currentGroup.id != db.groupID) {
                navigator.openLibrary(opening)
            }
            openingGroup = null
            ui.receiveCollections(getCollections())
            val restore = openingGroupCollection
            openingGroupCollection = null
            if (restore != null && isPlaceInOpenLibrary(restore)) {
                ui.setCollection(restore)
            } else {
                ui.redisplayItems()
            }
        }
        // else: a background refresh of a group the user has since left: loaded, not shown
    }

    /**
     * Reopens where the library screen was when the app last closed: a collection, or a
     * group and a collection in it. Called once My Library has loaded at startup, so
     * Back leads from there to My Library. A group opens only while group libraries
     * are on, and from the device if it was synced before.
     */
    private fun restorePlace(place: Pair<Int, String>) {
        val (groupId, collection) = place
        if (groupId == GroupInfo.NO_GROUP_ID) {
            if (collection != LibraryNavigator.ALL && isPlaceInOpenLibrary(collection)) {
                ui.setCollection(collection)
            }
            return
        }
        if (!preferences.isGroupLibrarySyncEnabled()) return
        zoteroDatabase.getGroups()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ groups ->
                val group = groups.firstOrNull { it.id == groupId } ?: return@subscribe
                // only if nothing else was opened meanwhile
                if (state.isUsingGroup() || state.currentCollection != LibraryNavigator.ALL) return@subscribe
                if (collection != LibraryNavigator.GROUP_ALL) openingGroupCollection = collection
                startGroupSync(group)
            }, { Log.e(TAG, "could not restore the open group: $it") })
    }

    /** Whether [collection] (a collection key or a fixed view) exists in the open library. */
    private fun isPlaceInOpenLibrary(collection: String): Boolean =
        SidebarView.entries.any { it.collectionId == collection } ||
                getCollectionFromKey(collection) != null

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

    override fun startSyncAnimation(useSmallAnimation: Boolean) {
        if (useSmallAnimation) {
            ui.showBasicSyncAnimation()
        } else {
            ui.showLibraryLoadingAnimation()
        }
    }

    override fun stopSyncAnimation() {
        ui.hideLibraryLoadingAnimation()
    }

    override fun createErrorAlert(title: String, message: String, onClick: () -> Unit) {
        ui.createErrorAlert(title, message, onClick)
    }

    override fun setSyncProgress(progress: Int, total: Int) {
        ui.updateLibraryRefreshProgress(progress, total, "")
    }

    override fun makeToastAlert(message: String) {
        ui.makeToastAlert(message)
    }
}