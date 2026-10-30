package com.mickstarify.zooforzotero.ZoteroAPI.Syncing

import android.util.Log
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.ZoteroAPI.APIKeyRevokedException
import com.mickstarify.zooforzotero.ZoteroAPI.ItemScope
import com.mickstarify.zooforzotero.ZoteroAPI.LibraryVersionMisMatchException
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroAPICollectionsResponse
import com.mickstarify.zooforzotero.ZoteroAPI.NetworkErrors
import com.mickstarify.zooforzotero.ZoteroAPI.UpToDateException
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroAPI
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroAPIItemsResponse
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.ItemsDownloadProgress
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.ZoteroDB
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.CompletableObserver
import io.reactivex.rxjava3.core.Observer
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Action
import io.reactivex.rxjava3.schedulers.Schedulers

class SyncManager (
    val zoteroAPI: ZoteroAPI,
    private val preferenceManager: PreferenceManager,
    private val zoteroDatabase: ZoteroDatabase,
    val syncChangeListener: OnSyncChangeListener,
    private val now: () -> Long = { System.currentTimeMillis() },
){

    // these three variables keep track of our download progress and are used to make sure
    // all 3 downloads finish.
    var loadingItems = false
    var loadingCollections = false
    var loadingTrash = false
    // past the downloads: deletions, then loading the library from the database
    private var finishing = false
    // a sync asked for while one runs (see startCompleteSync)
    private var queued: ZoteroDB? = null
    fun isSyncing() : Boolean {
        return (loadingItems || loadingCollections || loadingTrash || finishing)
    }

    // the library whose children a first sync is fetching, while loadingItems
    private var childrenGroup: Int? = null

    /** Whether a first sync of library [groupID] is still fetching its attachments and notes. */
    fun isLoadingChildren(groupID: Int): Boolean = loadingItems && childrenGroup == groupID

    /** The running sync's stages, or the last one's; null before the first. */
    var status: SyncStatus? = null
        private set

    private fun report(stage: SyncStage, change: (StageState) -> StageState) {
        val current = status ?: return
        status = current.with(stage, change).also { syncChangeListener.showSyncStatus(it) }
    }

    private fun begin(next: SyncStatus) {
        status = next
        syncChangeListener.showSyncStatus(next)
    }

    private fun failure(e: Throwable): (StageState) -> StageState = {
        // a timeout is the server being slow (a cold query on a large library), not the
        // device being offline: the other stages of the same sync may well have gone through
        val error = when {
            NetworkErrors.isTimeout(e) -> "Zotero didn't answer in time"
            NetworkErrors.isOffline(e) -> "offline"
            else -> e.message ?: e.toString()
        }
        it.copy(phase = StageState.Phase.FAILED, error = error)
    }

    private fun stageOf(scope: ItemScope) = if (scope == ItemScope.CHILDREN) SyncStage.CHILDREN else SyncStage.ITEMS

    /** Offline makes every request of a sync fail; say so once, as a toast. */
    private var reportedOffline = false

    private fun reportError(title: String, e: Throwable) {
        if (NetworkErrors.isOffline(e)) {
            if (!reportedOffline) {
                reportedOffline = true
                syncChangeListener.makeToastAlert(
                    "Offline: library not synced. Edits stay on this device until the next sync."
                )
            }
        } else {
            syncChangeListener.createErrorAlert(title, "Pull down to try again.\n\nDetails: ${e.message ?: e}") {}
        }
    }

    fun startCompleteSync(zoteroDB: ZoteroDB) {
        /*LIbrary loading will load in 3 stages,
        *
        * Stage 1, Downloading stage
        *   Here, 3 threads will run, downloading items, collections and trash.
        *   All three threads run independently and will all need to complete before stage 2.
        *
        * Stage 2. Downloading Deleted Items
        *   Here deleted items will need to be queried. This must complete after stage 1 because
        *   there may be items that get deleted before being synced.
        *
        * Stage 3.
        *   Loading, up until now  all downloaded data has been commited to the SQL DB. This has
        *   to now be loaded to memory in ZoteroDB class.
        *
        *
        *   How each stage is reached is somewhat complex but here in this method you will find stage 1.
         */

        if (isSyncing()) {
            // runs once this one is done: the running sync may be another library's (a
            // group refreshing), or have fetched before this request's edits went up
            Log.d("zotero", "sync of ${zoteroDB.groupID} queued behind the running one")
            queued = zoteroDB
            status?.let { begin(it.copy(queued = zoteroDB.groupID)) }
            syncChangeListener.startSyncAnimation()
            return
        }


        reportedOffline = false
        // show our loading animation.
        syncChangeListener.startSyncAnimation()

        Log.d("zotero", "initializing sync for ${zoteroDB.groupID}")
        begin(initialStatus(zoteroDB))
        if (status?.firstSync == true) countFirstSync(zoteroDB)
        loadItems(zoteroDB)
        loadCollections(zoteroDB)
        loadTrashedItems(zoteroDB)
    }

    /** The stages of a complete sync of [db] about to start; a first sync has two item passes. */
    private fun initialStatus(db: ZoteroDB): SyncStatus {
        val progress = db.getDownloadProgress()
        val first = progress?.scope == ItemScope.TOP || progress?.scope == ItemScope.CHILDREN ||
                (progress == null && db.getLibraryVersion() <= 0)
        val running = StageState(StageState.Phase.RUNNING)
        val items = if (!first) {
            mapOf(SyncStage.ITEMS to running)
        } else if (progress?.scope == ItemScope.CHILDREN) {
            mapOf(SyncStage.ITEMS to StageState(StageState.Phase.DONE), SyncStage.CHILDREN to running)
        } else {
            mapOf(SyncStage.ITEMS to running, SyncStage.CHILDREN to StageState())
        }
        return SyncStatus(
            db.groupID, first,
            items + mapOf(SyncStage.COLLECTIONS to running, SyncStage.TRASH to running, SyncStage.DELETIONS to StageState())
        )
    }

    /**
     * Fetches [db]'s changed items only. While a sync runs, a complete one is queued behind
     * it instead: a second item download alongside the running one wrote the same progress
     * and ended the sync twice.
     */
    /**
     * Asks Zotero how many items each pass of a first sync of [db] will fetch, so the
     * toolbar's percentage weighs them by size from the start (SyncStatus.percent). A
     * count a pass has reported itself is kept; without an answer, the passes go by halves.
     */
    private fun countFirstSync(db: ZoteroDB) {
        zoteroAPI.countItems(db.groupID, ItemScope.TOP)
            .zipWith(zoteroAPI.countItems(db.groupID, ItemScope.CHILDREN)) { top, children -> top to children }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (top, children) ->
                if (status?.running != true || status?.groupID != db.groupID) return@subscribe
                status = status?.copy(counted = true)
                report(SyncStage.ITEMS) { if (it.total == 0) it.copy(total = top) else it }
                report(SyncStage.CHILDREN) { if (it.total == 0) it.copy(total = children) else it }
            }, { Log.d("zotero", "counting the first sync's items failed: $it") })
    }

    fun startItemsSync(db: ZoteroDB){
        if (isSyncing()) {
            startCompleteSync(db)
            return
        }
        begin(SyncStatus(db.groupID, false, mapOf(
            SyncStage.ITEMS to StageState(StageState.Phase.RUNNING), SyncStage.DELETIONS to StageState()
        )))
        this.loadItems(db)
    }

    private fun loadCollections(db: ZoteroDB) {
        loadingCollections = true
        val sinceVersion = db.getCollectionsVersion()
        zoteroAPI.getCollections(sinceVersion, db.groupID).map { response ->
            // we will write collections to the database as we receive them.
            // this will be done using the rxjava thread.
            if (response.collections.isNotEmpty()) {
                val collectionObjects = response.collections.map { Collection(it, db.groupID) }
                zoteroDatabase.writeCollections(collectionObjects).blockingAwait()
            }
            response
        }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : Observer<ZoteroAPICollectionsResponse> {
                var version = -1
                override fun onComplete() {
                    Log.d("zotero", "finished getting collections.")
                    // only now are they complete up to this version; on an error the
                    // stored version stays and the next sync asks again
                    if (version > 0) db.setCollectionsVersion(version)
                    report(SyncStage.COLLECTIONS) { it.copy(phase = StageState.Phase.DONE) }
                    finishGetCollections(db)
                }

                override fun onSubscribe(d: Disposable) {
                }

                override fun onError(e: Throwable) {
                    if (e is UpToDateException) {
                        Log.d("zotero", "local copy of collections is already up to date.")
                        report(SyncStage.COLLECTIONS) { it.copy(phase = StageState.Phase.UP_TO_DATE) }
                    } else if (e is APIKeyRevokedException) {
                        report(SyncStage.COLLECTIONS, failure(e))
                        syncChangeListener.createErrorAlert(
                            "API key no longer valid",
                            "Zotero rejected this app's API key (it may have been revoked). " +
                                    "To sign in again, choose Settings → Sign out."
                        ) {}
                    } else {
                        report(SyncStage.COLLECTIONS, failure(e))
                        reportError("Couldn't sync collections", e)
                    }
                    finishGetCollections(db)
                }

                var received = 0
                override fun onNext(response: ZoteroAPICollectionsResponse) {
                    if (version == -1) version = response.lastModifiedVersion
                    received += response.collections.size
                    report(SyncStage.COLLECTIONS) { it.copy(done = received, total = response.totalResults) }
                }

            })
    }

    private fun loadTrashedItems(
        db: ZoteroDB
    ) {
        loadingTrash = true

        val observable =
            zoteroAPI.getTrashedItems(db.groupID, db.getTrashVersion()).map { response ->
                for (itemPojo in response.items) {
                    if (zoteroDatabase.containsItem(db.groupID, itemPojo.ItemKey).blockingGet()) {
                        // there is a subtle flaw here.
                        // If the item has changed since last sync, those changes won't be reflected in
                        // zoo. I will ignore this error for the sake of simplicity.
                        zoteroDatabase.moveItemToTrash(db.groupID, itemPojo.ItemKey).blockingAwait()
                    } else {
                        zoteroDatabase.writeItem(db.groupID, itemPojo).blockingAwait()
                        zoteroDatabase.moveItemToTrash(db.groupID, itemPojo.ItemKey).blockingAwait()
                    }
                }
                response
            }
        observable.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : Observer<ZoteroAPIItemsResponse> {
                var received = 0
                var trashLibraryVersion = 0
                override fun onComplete() {
                    // finished syncing trash.
                    db.setTrashVersion(trashLibraryVersion)
                    report(SyncStage.TRASH) { it.copy(phase = StageState.Phase.DONE) }
                    finishGetTrash(db)
                }

                override fun onSubscribe(d: Disposable) {
                    Log.d("zotero", "beginning sync request for trash")
                }

                override fun onNext(response: ZoteroAPIItemsResponse) {
                    received += response.items.size
                    trashLibraryVersion = response.LastModifiedVersion
                    report(SyncStage.TRASH) { it.copy(done = received, total = response.totalResults) }
                    Log.d("zotero", "received ${received} of ${response.totalResults} trash items")
                }

                override fun onError(e: Throwable) {
                    if (e is UpToDateException) {
                        Log.d("zotero", "trashed items has not changed")
                        report(SyncStage.TRASH) { it.copy(phase = StageState.Phase.UP_TO_DATE) }
                    } else {
                        Log.e("zotero", "got error from request to /trash $e")
                        report(SyncStage.TRASH, failure(e))
                    }
                    finishGetTrash(db)
                }

            })
    }

    private fun loadItems(
        db: ZoteroDB,
    ) {
        loadingItems = true
        val progress = db.getDownloadProgress()
        when {
            progress?.scope == ItemScope.CHILDREN ->
                downloadItems(db, ItemScope.CHILDREN, progress, progress.libraryVersion)
            progress != null -> downloadItems(db, progress.scope, progress, -1)
            // a first sync, or one starting over: top-level items first (see ItemScope)
            db.getLibraryVersion() <= 0 -> downloadItems(db, ItemScope.TOP, null, -1)
            else -> downloadItems(db, ItemScope.ALL, null, -1)
        }
    }

    /**
     * Downloads the items in [scope], continuing [progress] if a download of them stopped
     * partway. [startVersion] is the library version a first sync began at once its
     * top-level pass fixed it, else -1: the first page's. A first sync's [ItemScope.TOP]
     * pass is followed by its [ItemScope.CHILDREN] pass, with the library open meanwhile;
     * only the end of that sets the items version, so a sync cut off in between never
     * leaves later syncs skipping the children.
     */
    private fun downloadItems(
        db: ZoteroDB,
        scope: ItemScope,
        progress: ItemsDownloadProgress?,
        startVersion: Int,
    ) {
        childrenGroup = if (scope == ItemScope.CHILDREN) db.groupID else null
        if (scope == ItemScope.CHILDREN) openBeforeChildren(db)
        val itemsObservable =
            zoteroAPI.getItems(
                db.getLibraryVersion(),
                db.groupID,
                downloadProgress = progress,
                scope = scope
            )
        itemsObservable.map { response ->
            Log.d("zotero", "About to write ${response.items.size} items to DB for groupID=${db.groupID}")
            zoteroDatabase.writeItemPOJOs(db.groupID, response.items).blockingAwait()
            Log.d("zotero", "Finished writing ${response.items.size} items to DB for groupID=${db.groupID}")
            response // pass it on.
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : Observer<ZoteroAPIItemsResponse> {
                var libraryVersion = startVersion // -1: replaced in onNext()
                var downloaded = progress?.nDownloaded ?: 0
                var shownAt = now()
                var shownOnce = false
                override fun onComplete() {
                    Log.d("zotero", "load Items ($scope) oncomplete")
                    if (scope == ItemScope.TOP) {
                        // stored before the library opens, so a sync cut off now resumes here
                        db.setDownloadProgress(ItemsDownloadProgress(libraryVersion, 0, 0, ItemScope.CHILDREN))
                        report(SyncStage.ITEMS) { it.copy(phase = StageState.Phase.DONE) }
                        // keeps the count asked for at the start (countFirstSync)
                        report(SyncStage.CHILDREN) { it.copy(phase = StageState.Phase.RUNNING) }
                        syncChangeListener.makeToastAlert("Library ready. Attachment details and notes are still downloading.")
                        downloadItems(db, ItemScope.CHILDREN, null, libraryVersion)
                        return
                    }
                    if (scope == ItemScope.ALL && db.getLibraryVersion() > -1) {
                        if (downloaded == 0) {
                            syncChangeListener.makeToastAlert("Already up to date.")
                        } else {
                            syncChangeListener.makeToastAlert(if (downloaded == 1) "Updated 1 item." else "Updated $downloaded items.")
                        }
                    }
                    report(stageOf(scope)) { it.copy(phase = StageState.Phase.DONE) }
                    db.destroyDownloadProgress()
                    db.setItemsVersion(libraryVersion)
                    db.updateDatabaseLastSyncedTimestamp()
                    finishGetItems(db)
                }

                override fun onSubscribe(d: Disposable) {
                }

                override fun onNext(response: ZoteroAPIItemsResponse) {
                    // The version the download started from, not the latest page's: if the
                    // library changes while it pages through, items changed meanwhile can
                    // move to pages already read. Stored as the newer version, they would
                    // never be fetched; from the first, the next sync fetches them.
                    if (this.libraryVersion == -1) this.libraryVersion = response.LastModifiedVersion
                    this.downloaded += response.items.size

                    if (this.downloaded < response.totalResults)
                        db.setDownloadProgress(
                            ItemsDownloadProgress(
                                this.libraryVersion,
                                this.downloaded,
                                response.totalResults,
                                scope
                            )
                        )
                    report(stageOf(scope)) {
                        it.copy(phase = StageState.Phase.RUNNING, done = downloaded, total = response.totalResults)
                    }
                    Log.d("zotero", "got ${this.downloaded} of ${response.totalResults} items ($scope)")
                    // a first sync's list fills as the items come: the top-level items from
                    // the first page on, then the PDF icons, filters and note search
                    val firstPage = scope == ItemScope.TOP && !shownOnce
                    if (scope != ItemScope.ALL && (firstPage || now() - shownAt >= SHOW_NEW_ITEMS_EVERY_MS)) {
                        shownAt = now()
                        shownOnce = true
                        syncChangeListener.showNewItems(db)
                    }
                }

                override fun onError(e: Throwable) {
                    if (e is UpToDateException) {
                        Log.d("zotero", "our items are already up to date.")
                        syncChangeListener.makeToastAlert("Already up to date.")
                        report(stageOf(scope)) { it.copy(phase = StageState.Phase.UP_TO_DATE) }
                        db.updateDatabaseLastSyncedTimestamp()
                    } else if (e is LibraryVersionMisMatchException && scope == ItemScope.CHILDREN) {
                        // the top-level items stay; the children again from the start. The
                        // first pass's version is still the one stored at the end, so the
                        // next sync fetches whatever changed meanwhile.
                        Log.d("zotero", "library changed during the children pass, restarting it")
                        db.setDownloadProgress(ItemsDownloadProgress(libraryVersion, 0, 0, ItemScope.CHILDREN))
                        report(SyncStage.CHILDREN) { StageState(StageState.Phase.RUNNING) }
                        downloadItems(db, ItemScope.CHILDREN, null, libraryVersion)
                        return
                    } else if (e is LibraryVersionMisMatchException) {
                        // we need to redownload items but without the progress.
                        Log.d("zotero", "mismatched, reloading items.")
                        db.destroyDownloadProgress()
                        report(SyncStage.ITEMS) { StageState(StageState.Phase.RUNNING) }
                        syncChangeListener.makeToastAlert("The library changed in Zotero during the sync. Starting over.")
                        loadItems(db)
                        return
                    } else {
                        report(stageOf(scope), failure(e))
                        reportError("Couldn't sync items", e)
                        Log.e("zotero", "${e}")
                        Log.e("zotero", "${e.stackTrace}")
                    }
                    finishGetItems(db)
                }
            })
    }

    /** Shows the library while its attachment records and notes download, under the small animation. */
    private fun openBeforeChildren(db: ZoteroDB) {
        syncChangeListener.finishLibrarySync(db) {
            // opening hides the animation; the download may have ended meanwhile
            if (loadingItems) syncChangeListener.startSyncAnimation()
        }
    }

    private fun finishGetCollections(db: ZoteroDB) {
        loadingCollections = false
        if (!loadingItems && !loadingTrash) {
            loadLibraryStage2(db)
        }
    }

    private fun finishGetItems(db: ZoteroDB) {
        loadingItems = false
        if (!loadingCollections && !loadingTrash) {
            loadLibraryStage2(db)
        }
    }

    private fun finishGetTrash(db: ZoteroDB) {
        loadingTrash = false
        if (!loadingItems && !loadingCollections) {
            loadLibraryStage2(db)
        }
    }

    fun loadLibraryStage2(db: ZoteroDB) {
        // as  defined above, stage 2 handles deleted files.
        if (loadingTrash || loadingCollections || loadingItems) {
            throw Exception("Error cannot proceed to stage 2 if library still loading.")
        }
        finishing = true
        report(SyncStage.DELETIONS) { it.copy(phase = StageState.Phase.RUNNING) }

        updateDeletedEntries(db)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object :
                CompletableObserver {
                override fun onSubscribe(d: Disposable) {
                    // nothing.
                }

                override fun onComplete() {
                    report(SyncStage.DELETIONS) { it.copy(phase = StageState.Phase.DONE) }
                    syncChangeListener.finishLibrarySync(db) { finishSync() }
                }

                override fun onError(e: Throwable) {
                Log.e("zotero", "there was an error processing deleted Entries, ${e}")
                report(SyncStage.DELETIONS, failure(e))
                reportError("Couldn't sync deletions", e)
                // finish anyway: it reloads what's stored and stops the sync animation
                syncChangeListener.finishLibrarySync(db) { finishSync() }
            }
        })


    }

    /** After the library is loaded and the animation stopped: runs a queued sync. */
    private fun finishSync() {
        finishing = false
        status?.let { begin(it.copy(running = false, queued = null)) }
        queued?.let { db ->
            queued = null
            startCompleteSync(db)
        }
    }

    fun updateDeletedEntries(db: ZoteroDB): Completable {
        /* Checks for deleted entries on the zotero servers and mirrors those changes on the local database. */
        // we have to assume the library is loaded.

        val deletedItemsCheckVersion = db.getLastDeletedItemsCheckVersion()
        val libraryVersion = db.getLibraryVersion()
        if (deletedItemsCheckVersion == libraryVersion) {
            Log.d(
                "zotero",
                "not checking deleted items because library hasn't changed. ${libraryVersion}"
            )
            return Completable.complete() // our job is done, there is nothing to check.
        }

        val completable = Completable.fromAction(Action {
            val deletedEntriesPojo = zoteroAPI.getDeletedEntries(deletedItemsCheckVersion, db.groupID).blockingGet()
            for (itemKey in deletedEntriesPojo.items){
                zoteroDatabase.deleteItem(itemKey).blockingAwait()
            }
            for (collectionKey in deletedEntriesPojo.collections){
                zoteroDatabase.deleteCollection(collectionKey).blockingAwait()
            }

            Log.d(
                "zotero",
                "Setting deletedLibraryVersion to $libraryVersion from $deletedItemsCheckVersion"
            )
            db.setLastDeletedItemsCheckVersion(libraryVersion)
        })

        return completable
    }

    companion object {
        // how often the list is redrawn while a first sync's items arrive: each redraw
        // re-reads the rows on screen, and the list grows under the reader
        const val SHOW_NEW_ITEMS_EVERY_MS = 10_000L
    }
}
