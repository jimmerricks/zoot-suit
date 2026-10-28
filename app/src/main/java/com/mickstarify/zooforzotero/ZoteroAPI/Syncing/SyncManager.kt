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
    private var queued: Pair<ZoteroDB, Boolean>? = null
    fun isSyncing() : Boolean {
        return (loadingItems || loadingCollections || loadingTrash || finishing)
    }

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

    fun startCompleteSync(zoteroDB: ZoteroDB, useSmallLoadingAnimation: Boolean) {
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
            queued = Pair(zoteroDB, useSmallLoadingAnimation)
            syncChangeListener.startSyncAnimation(useSmallLoadingAnimation)
            return
        }


        reportedOffline = false
        // show our loading animation.
        syncChangeListener.startSyncAnimation(useSmallLoadingAnimation)

        Log.d("zotero", "initializing sync for ${zoteroDB.groupID}")
        loadItems(zoteroDB, useSmallLoadingAnimation)
        loadCollections(zoteroDB)
        loadTrashedItems(zoteroDB)
    }

    fun startItemsSync(db: ZoteroDB){
        this.loadItems(db, true)
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
                    finishGetCollections(db)
                }

                override fun onSubscribe(d: Disposable) {
                }

                override fun onError(e: Throwable) {
                    if (e is UpToDateException) {
                        Log.d("zotero", "local copy of collections is already up to date.")
                    } else if (e is APIKeyRevokedException) {
                        syncChangeListener.createErrorAlert(
                            "API key no longer valid",
                            "Zotero rejected this app's API key (it may have been revoked). " +
                                    "To sign in again, choose Settings → Sign out."
                        ) {}
                    } else {
                        reportError("Couldn't sync collections", e)
                    }
                    finishGetCollections(db)
                }

                override fun onNext(response: ZoteroAPICollectionsResponse) {
                    if (version == -1) version = response.lastModifiedVersion
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
                    finishGetTrash(db)
                }

                override fun onSubscribe(d: Disposable) {
                    Log.d("zotero", "beginning sync request for trash")
                }

                override fun onNext(response: ZoteroAPIItemsResponse) {
                    received += response.items.size
                    trashLibraryVersion = response.LastModifiedVersion
                    Log.d("zotero", "received ${received} of ${response.totalResults} trash items")
                }

                override fun onError(e: Throwable) {
                    if (e is UpToDateException) {
                        Log.d("zotero", "trashed items has not changed")
                    } else {
                        Log.e("zotero", "got error from request to /trash $e")
                    }
                    finishGetTrash(db)
                }

            })
    }

    private fun loadItems(
        db: ZoteroDB,
        useSmallLoadingAnimation: Boolean = false
    ) {
        loadingItems = true
        val progress = db.getDownloadProgress()
        when {
            progress?.scope == ItemScope.CHILDREN ->
                downloadItems(db, ItemScope.CHILDREN, progress, progress.libraryVersion, true)
            progress != null -> downloadItems(db, progress.scope, progress, -1, useSmallLoadingAnimation)
            // a first sync, or one starting over: top-level items first (see ItemScope)
            db.getLibraryVersion() <= 0 -> downloadItems(db, ItemScope.TOP, null, -1, useSmallLoadingAnimation)
            else -> downloadItems(db, ItemScope.ALL, null, -1, useSmallLoadingAnimation)
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
        useSmallLoadingAnimation: Boolean
    ) {
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
                override fun onComplete() {
                    Log.d("zotero", "load Items ($scope) oncomplete")
                    if (scope == ItemScope.TOP) {
                        // stored before the library opens, so a sync cut off now resumes here
                        db.setDownloadProgress(ItemsDownloadProgress(libraryVersion, 0, 0, ItemScope.CHILDREN))
                        syncChangeListener.makeToastAlert("Library ready. Attachment details and notes are still downloading.")
                        downloadItems(db, ItemScope.CHILDREN, null, libraryVersion, true)
                        return
                    }
                    if (scope == ItemScope.ALL && db.getLibraryVersion() > -1) {
                        if (downloaded == 0) {
                            syncChangeListener.makeToastAlert("Already up to date.")
                        } else {
                            syncChangeListener.makeToastAlert(if (downloaded == 1) "Updated 1 item." else "Updated $downloaded items.")
                        }
                    }
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
                    if (!useSmallLoadingAnimation) {
                        syncChangeListener.setSyncProgress(
                            downloaded,
                            response.totalResults)
                    }
                    Log.d("zotero", "got ${this.downloaded} of ${response.totalResults} items ($scope)")
                    if (scope == ItemScope.CHILDREN && now() - shownAt >= SHOW_CHILDREN_EVERY_MS) {
                        // the open library's PDF icons, filters and note search fill in as they come
                        shownAt = now()
                        syncChangeListener.showNewItems(db)
                    }
                }

                override fun onError(e: Throwable) {
                    if (e is UpToDateException) {
                        Log.d("zotero", "our items are already up to date.")
                        syncChangeListener.makeToastAlert("Already up to date.")
                        db.updateDatabaseLastSyncedTimestamp()
                    } else if (e is LibraryVersionMisMatchException && scope == ItemScope.CHILDREN) {
                        // the top-level items stay; the children again from the start. The
                        // first pass's version is still the one stored at the end, so the
                        // next sync fetches whatever changed meanwhile.
                        Log.d("zotero", "library changed during the children pass, restarting it")
                        db.setDownloadProgress(ItemsDownloadProgress(libraryVersion, 0, 0, ItemScope.CHILDREN))
                        downloadItems(db, ItemScope.CHILDREN, null, libraryVersion, true)
                        return
                    } else if (e is LibraryVersionMisMatchException) {
                        // we need to redownload items but without the progress.
                        Log.d("zotero", "mismatched, reloading items.")
                        db.destroyDownloadProgress()
                        syncChangeListener.makeToastAlert("The library changed in Zotero during the sync. Starting over.")
                        loadItems(db, useSmallLoadingAnimation = false)
                        return
                    } else {
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
            if (loadingItems) syncChangeListener.startSyncAnimation(true)
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

        updateDeletedEntries(db)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object :
                CompletableObserver {
                override fun onSubscribe(d: Disposable) {
                    // nothing.
                }

                override fun onComplete() {
                    syncChangeListener.finishLibrarySync(db) { finishSync() }
                }

                override fun onError(e: Throwable) {
                Log.e("zotero", "there was an error processing deleted Entries, ${e}")
                reportError("Couldn't sync deletions", e)
                // finish anyway: it reloads what's stored and stops the sync animation
                syncChangeListener.finishLibrarySync(db) { finishSync() }
            }
        })


    }

    /** After the library is loaded and the animation stopped: runs a queued sync. */
    private fun finishSync() {
        finishing = false
        queued?.let { (db, small) ->
            queued = null
            startCompleteSync(db, small)
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
        // how often the list is redrawn while a first sync's children arrive: each redraw
        // re-reads the rows on screen, and a filtered list grows under the reader
        const val SHOW_CHILDREN_EVERY_MS = 10_000L
    }
}
