package com.mickstarify.zooforzotero.LibraryActivity

import android.util.Log
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.OnSyncChangeListener
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.StageState
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.SyncManager
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.SyncStage
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.SyncStatus
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroAPI
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import com.mickstarify.zooforzotero.ZoteroStorage.EditedAttachments
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditStore
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.ZoteroDB
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.LinkedList
import java.util.concurrent.TimeUnit

private const val TAG = "LibrarySync"

/**
 * Syncing the library screen's libraries and opening them: a sync of My Library sends
 * this device's edits, then fetches Zotero's changes (SyncManager, which reports back
 * here); a library is opened after a sync, at startup and when a group is chosen, and at
 * startup the screen goes back to the place it was left at.
 *
 * @param api the API client, built on first use (see LibraryComponents)
 * @param refreshPendingEdits works out again what's on the device and waits to sync
 * @param selectedItem the item open in the item view, if any
 */
class LibrarySync(
    private val ui: LibraryUi,
    private val library: OpenLibrary,
    private val api: () -> ZoteroAPI,
    private val zoteroDatabase: ZoteroDatabase,
    private val preferences: PreferenceManager,
    private val itemEdits: ItemEditStore,
    private val editedAttachments: EditedAttachments,
    private val lists: LibraryLists,
    private val attachments: LibraryAttachments,
    private val edits: LibraryItemEdits,
    private val refreshPendingEdits: () -> Unit,
    private val selectedItem: () -> Item?,
) : OnSyncChangeListener {
    private val navigator get() = library.navigator
    private val state get() = library.state
    private val zoteroDB get() = library.db

    private val syncManager: SyncManager by lazy { SyncManager(api(), preferences, zoteroDatabase, this) }

    /** Fetches the open library's changed items (after a note was changed in Zotero). */
    fun syncItems() = syncManager.startItemsSync(db = zoteroDB)

    /** Forgets a group being opened (Settings turned group libraries off). */
    fun forgetOpeningGroup() {
        openingGroup = null
        openingGroupCollection = null
    }

    /**
     * A sync sends this device's edits first, then fetches the library's changes. Edits
     * that can't go up (offline, refused) stay on the device and are tried again at the
     * next sync; the download half runs either way.
     */
    fun refreshLibrary(useSmallLoadingAnimation: Boolean = false) {
        if (!state.isUsingGroup()) {
            showEditsStage(StageState(StageState.Phase.RUNNING))
            val pull = {
                editsStage = StageState(StageState.Phase.DONE)
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
            startGroupSync(state.currentGroup, refresh = true)
        }
    }

    // My Library's edits going up before its download, for the sync's details
    private var editsStage: StageState? = null

    private fun showEditsStage(stage: StageState) {
        editsStage = stage
        val running = syncManager.status?.takeIf { it.running }
            ?: SyncStatus(GroupInfo.NO_GROUP_ID, isFirstSync(), emptyMap())
        showSyncStatus(running)
    }

    /** The sync's stages, with My Library's edits sent before it. */
    override fun showSyncStatus(status: SyncStatus) {
        val edits = editsStage
        ui.showSyncStatus(
            if (edits != null && status.groupID == GroupInfo.NO_GROUP_ID) status.with(SyncStage.EDITS) { edits }
            else status
        )
        if (!status.running) editsStage = null
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

    fun downloadLibrary(doRefresh: Boolean = false, useSmallLoadingAnimation: Boolean = false) {
        if (!doRefresh && zoteroDB.isOpen()) {
            Log.d("zotero", "not resyncing library, already have a copy.")
            return
        }
        syncManager.startCompleteSync(zoteroDB, useSmallLoadingAnimation)
    }

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
    fun startGroupSync(group: GroupInfo, refresh: Boolean = false) {
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

    fun isSyncing(): Boolean = syncManager.isSyncing()

    /**
     * Continues an item download that stopped partway (offline, or cut off by Doze while
     * the display slept) from the page it reached, and returns true; false if there is
     * none or a sync is running. Downloads only: the sync that stopped sent this device's
     * edits when it started.
     */
    fun resumeInterruptedSync(): Boolean {
        if (!library.isLoaded() || syncManager.isSyncing() || zoteroDB.getDownloadProgress() == null) return false
        Log.d(TAG, "resuming the interrupted item download for ${zoteroDB.groupID}")
        downloadLibrary(doRefresh = true, useSmallLoadingAnimation = false)
        return true
    }

    fun destroyLibrary() {
        zoteroDB.clearItemsVersion()
        zoteroDatabase.deleteEverything()
    }

    fun loadLibraryLocally(onFinish: () -> Unit = {}) {
        if (!zoteroDB.isOpen())
            finishLibrarySync(zoteroDB) {
                onFinish()
            }
    }

    fun isFirstSync(): Boolean {
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
            runCatching { api() } // built here rather than on the main thread; see its declaration
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
        api().getChildItems(item.itemKey, db.groupID)
            .flatMapCompletable { zoteroDatabase.writeItemPOJOs(db.groupID, it) }
            .andThen(Maybe.fromCallable { zoteroDatabase.loadItemsByKeys(listOf(item.itemKey)).firstOrNull() })
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ fresh ->
                if (childKeys(fresh) == before) return@subscribe
                item.attachments = fresh.attachments
                item.notes = fresh.notes
                ui.showEditedItem(item)
                if (selectedItem() === item) ui.refreshItemView()
            }, { Log.d(TAG, "fetching the children of ${item.itemKey} failed: $it") })
    }

    override fun showNewItems(db: ZoteroDB) {
        if (db.groupID != zoteroDB.groupID || !library.isLoaded()) return
        lists.invalidate()
        ui.redisplayItems()
    }

    /** Shows library [db], just opened: My Library, the group being opened, or a refresh of the open one. */
    private fun showOpenedLibrary(db: ZoteroDB) {
        if (db.groupID == GroupInfo.NO_GROUP_ID) {
            ui.receiveCollections(library.collections)
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
            ui.receiveCollections(library.collections)
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
                library.collection(collection) != null

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
