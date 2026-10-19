package com.mickstarify.zooforzotero.LibraryActivity

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.util.Log
import com.google.gson.JsonObject
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.SyncSetup.AuthenticationStorage
import com.mickstarify.zooforzotero.ZoteroAPI.AlreadyUploadedException
import io.reactivex.rxjava3.core.Single
import com.mickstarify.zooforzotero.ZoteroAPI.RemoteAttachmentChangedException
import com.mickstarify.zooforzotero.ZoteroAPI.AttachmentFileCheck
import com.mickstarify.zooforzotero.ZoteroAPI.AttachmentUploadBatch
import com.mickstarify.zooforzotero.ZoteroAPI.DamagedAttachmentException
import com.mickstarify.zooforzotero.ZoteroAPI.AttachmentUploadPreflight
import com.mickstarify.zooforzotero.ZoteroAPI.DeleteItemListener
import com.mickstarify.zooforzotero.ZoteroAPI.DownloadProgress
import com.mickstarify.zooforzotero.ZoteroAPI.ItemChangedSinceException
import com.mickstarify.zooforzotero.ZoteroAPI.ItemLockedException
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.OnSyncChangeListener
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.SyncManager
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroAPI
import com.mickstarify.zooforzotero.ZoteroAPI.ZoteroNotFoundException
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentLocalState
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentOpenCheck
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentStorageManager
import com.mickstarify.zooforzotero.ZoteroStorage.EditedAttachmentFinder
import com.mickstarify.zooforzotero.ZoteroStorage.Database.AttachmentInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.ZoteroDB
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.ZoteroDBPicker
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.ZoteroGroupDB
import com.mickstarify.zooforzotero.di.SingletonComponentsEntryPoint
import dagger.hilt.android.EntryPointAccessors
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.CompletableObserver
import io.reactivex.rxjava3.core.MaybeObserver
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Observer
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.FileNotFoundException
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection as ZoteroCollection

private const val TAG = "LibraryActivityModel"

class LibraryActivityModel(private val presenter: Contract.Presenter, val context: Context) :
    Contract.Model, OnSyncChangeListener {

    // stores the current item being viewed by the user. (useful for refreshing the view)
    var selectedItem: Item? = null
    var isDisplayingItems = false

    private lateinit var zoteroAPI: ZoteroAPI

    private var syncManager: SyncManager

    val zoteroDatabase: ZoteroDatabase
    val attachmentStorageManager: AttachmentStorageManager
    val preferences: PreferenceManager

    private val zoteroGroupDB =
        ZoteroGroupDB(
            context
        )
    private var zoteroDBPicker =
        ZoteroDBPicker(
            ZoteroDB(
                context,
                groupID = -1
            ), zoteroGroupDB
        )
    private var groups: List<GroupInfo>? = null


    val navigator = LibraryNavigator()
    val states get() = navigator.states
    val state: LibraryModelState
        get() = navigator.current
    private var zoteroDB: ZoteroDB by zoteroDBPicker

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
            if (isUploading()) {
                pull()
                return
            }
            Single.fromCallable { findEditedAttachments() }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ edited ->
                    if (edited.isEmpty()) pull() else uploadAttachments(edited, onDone = pull)
                }, { e ->
                    Log.e("zotero", "looking for edited attachments failed: $e")
                    pull()
                })
        } else {
            this.startGroupSync(state.currentGroup, refresh = true)
        }
    }

    fun shouldIUpdateLibrary(): Boolean {
        val currentTimestamp = System.currentTimeMillis()
        val lastModified = zoteroDB.getLastSyncedTimestamp()
        Log.d("zotero", "last modified = $lastModified")

        if (TimeUnit.MILLISECONDS.toHours(currentTimestamp - lastModified) >= 24) {
            return true
        }
        return false
    }

    override fun isLoaded(): Boolean {
        return zoteroDB.isPopulated()
    }

    private fun filterItems(items: List<Item>): List<Item> {
        return LibraryItemFilter.applyDisplayFilters(
            items,
            onlyNotes = preferences.getIsShowingOnlyNotes(),
            onlyPdfs = preferences.getIsShowingOnlyPdfs(),
            attachmentsFor = ::getAttachments
        )
    }

    override fun getGroupByTitle(groupTitle: String): GroupInfo? {
        return this.groups?.filter { it.name == groupTitle }?.firstOrNull()
    }

    override fun downloadLibrary(doRefresh: Boolean, useSmallLoadingAnimation: Boolean) {
        if (!doRefresh && zoteroDB.isPopulated()) {
            Log.d("zotero", "not resyncing library, already have a copy.")
            return
        }
        if (zoteroDB.getLibraryVersion() <= 0) {
            this.performedCleanSync = true
        }
        syncManager.startCompleteSync(zoteroDB, useSmallLoadingAnimation)
    }

    override fun getLibraryItems(): List<Item> {
        val items = zoteroDB.getDisplayableItems()
        Log.d(TAG, "getLibraryItems() - got ${items.size} items from zoteroDB.getDisplayableItems()")
        val filteredItems = filterItems(items)
        Log.d(TAG, "getLibraryItems() - after filtering: ${filteredItems.size} items")
        return filteredItems
    }

    override fun getItemsFromCollection(collectionKey: String): List<Item> {
        val items = zoteroDB.getItemsFromCollection(collectionKey)
        Log.d(TAG, "getItemsFromCollection($collectionKey) - got ${items.size} items from zoteroDB")
        val filteredItems = filterItems(items)
        Log.d(TAG, "getItemsFromCollection($collectionKey) - after filtering: ${filteredItems.size} items")
        return filteredItems
    }

    override fun getSubCollections(collectionKey: String): List<ZoteroCollection> {
//        val collectionKey = zoteroDB.getCollectionId(collectionName)
        return zoteroDB.getSubCollectionsFor(collectionKey)
    }


    override fun getCollections(): List<ZoteroCollection> {
        return zoteroDB.collections ?: LinkedList()
    }

    override fun getAttachments(itemKey: String): List<Item> {
        return zoteroDB.getAttachments(itemKey)
    }

    override fun getMyPublications(): List<Item> {
        return zoteroDB.myPublications ?: LinkedList()
    }

    override fun filterCollections(query: String): List<ZoteroCollection> {
        return LibraryItemFilter.collectionsMatching(zoteroDB.collections, query)
    }

    override fun openPDF(attachment: Item) {
        /* This method creates an intent to open a PDF for Android. */

        run {
            try {
                val intent = attachmentStorageManager.openAttachment(attachment)
                context.startActivity(intent)
            } catch (exception: ActivityNotFoundException) {
                presenter.createErrorAlert("No PDF Viewer installed",
                    "There is no app that handles ${attachment.getFileExtension()} documents available on your device. Would you like to install one?",
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=com.xodo.pdf.reader")
                                )
                            )
                        } catch (e: ActivityNotFoundException) {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://play.google.com/store/apps/details?id=com.xodo.pdf.reader")
                                )
                            )
                        }
                    })
            } catch (e: IllegalArgumentException) {
                presenter.createErrorAlert("Error opening File",
                    "There was an error opening your PDF." +
                            " If you are on a huawei device, this is a known error with their implementation of file" +
                            " access. Try changing the storage location to a custom path in settings.",
                    {})
            } catch (e: FileNotFoundException) {
                presenter.createErrorAlert("File not found",
                    "There was an error opening your PDF." +
                            "Please redownload your file.",
                    {})
            }
        }
    }

    override fun openAttachment(item: Item) {
        /* This is the point of entry when a user clicks an attachment on the UI.
        *  We must decide whether we want to intitiate a download or just open a local copy. */

        // first check to see if we are opening a linked attachment
        if (item.data["linkMode"] == "linked_file") {
            val intent = attachmentStorageManager.openLinkedAttachment(item)
            if (intent != null) {
                context.startActivity(intent)
            } else {
                presenter.makeToastAlert("Error, could not find linked attachment ${item.data["path"]}")
            }
            return
        }
        presenter.showLoadingAlertDialog("Opening your attachment")
        // check to see if the attachment exists but is invalid
        val attachmentExists: Boolean
        try {
            attachmentExists = attachmentStorageManager.checkIfAttachmentExists(
                item,
                checkMd5 = false
            )
        } catch (e: Exception) {
            presenter.makeToastAlert("could not open attachment, file not found.")
            return
        }
        if (attachmentExists) {
            // Is the local copy based on Zotero's current version? See AttachmentOpenCheck.
            val localMd5 = try {
                attachmentStorageManager.calculateMd5(item)
            } catch (e: Exception) {
                ""
            }
            val base = attachmentBase(item)
            when (AttachmentOpenCheck.decide(base, item.getMd5Key(), localMd5)) {
                AttachmentOpenCheck.Action.REDOWNLOAD -> {
                    presenter.makeToastAlert("Zotero has a newer version of this file. Downloading it.")
                    presenter.hideLoadingAlertDialog()
                    presenter.updateAttachmentDownloadProgress(0, -1)
                    attachmentStorageManager.deleteAttachment(item)
                    downloadAttachment(item)
                    return
                }
                AttachmentOpenCheck.Action.ASK_EDITED_STALE -> {
                    presenter.hideLoadingAlertDialog()
                    presenter.createYesNoPrompt(
                        "Newer version in Zotero",
                        "This file was changed on another device after you downloaded it, and your " +
                                "copy here has changes of its own. They can't be uploaded without " +
                                "overwriting the newer version, so uploading this copy will be refused.\n\n" +
                                "Download the newer version (your changes on this device are replaced), " +
                                "or open your copy and save your annotations as a new file.",
                        "Download newer", "Open my copy", {
                            presenter.updateAttachmentDownloadProgress(0, -1)
                            attachmentStorageManager.deleteAttachment(item)
                            downloadAttachment(item)
                        }, {
                            openPDF(item)
                        }
                    )
                    return
                }
                AttachmentOpenCheck.Action.RECORD_BASE_AND_OPEN -> recordBase(item, localMd5)
                AttachmentOpenCheck.Action.OPEN -> {}
            }
            // check the validity of the attachment before opening. Only for copies without a
            // base: with one, AttachmentOpenCheck already compared it with Zotero's version, and
            // a difference that's left is this device's own edit waiting to be uploaded, which
            // "redownload" would throw away.
            presenter.showLoadingAlertDialog("Verifying MD5 Checksum")
            if (base == null && preferences.shouldCheckMd5SumBeforeOpening() && zoteroDB.hasMd5Key(
                    item,
                    onlyWebdav = preferences.isWebDAVEnabled()
                ) && !attachmentStorageManager.validateMd5ForItem(
                    item,
                    zoteroDB.getMd5Key(item)
                )
            ) {
                presenter.createYesNoPrompt(
                    "File conflict",
                    "Your local copy is different to the server's. Would you like to redownload the server's copy?",
                    "Yes", "No", {
                        presenter.updateAttachmentDownloadProgress(0, -1)
                        attachmentStorageManager.deleteAttachment(item)
                        downloadAttachment(item)
                    }, {
                        presenter.hideLoadingAlertDialog()
                        openPDF(item)
                    }
                )
                return
            } else {
                presenter.hideLoadingAlertDialog()
                openPDF(item)
            }

        } else {
            presenter.hideLoadingAlertDialog()
            presenter.updateAttachmentDownloadProgress(0, -1)
            downloadAttachment(item)
        }

    }

    override fun filterItems(query: String): List<Item> {
        return LibraryItemFilter.matchingQuery(zoteroDB.items, query)
    }

    var downloadDisposable: Disposable? = null

    /**
     * Puts [item]'s file on the device without opening it. A copy that's already there is
     * kept, unless it's an unedited copy of an older version (replaced) or an edited copy
     * of one (asks, as opening does).
     */
    fun downloadAttachmentOnly(item: Item) {
        if (item.data["linkMode"] == "linked_file" || item.data["linkMode"] == "linked_url") {
            presenter.makeToastAlert("Linked attachments aren't stored in Zotero.")
            return
        }
        val exists = try {
            attachmentStorageManager.checkIfAttachmentExists(item, checkMd5 = false)
        } catch (e: Exception) {
            false
        }
        if (!exists) {
            presenter.updateAttachmentDownloadProgress(0, -1)
            downloadAttachment(item, openWhenDone = false)
            return
        }
        val localMd5 = runCatching { attachmentStorageManager.calculateMd5(item) }.getOrDefault("")
        when (AttachmentOpenCheck.decide(attachmentBase(item), item.getMd5Key(), localMd5)) {
            AttachmentOpenCheck.Action.REDOWNLOAD -> {
                attachmentStorageManager.deleteAttachment(item)
                presenter.updateAttachmentDownloadProgress(0, -1)
                downloadAttachment(item, openWhenDone = false)
            }
            AttachmentOpenCheck.Action.ASK_EDITED_STALE -> presenter.createYesNoPrompt(
                "Newer version in Zotero",
                "This file was changed on another device after you downloaded it, and your " +
                        "copy here has changes of its own. Downloading the newer version " +
                        "replaces your changes on this device.",
                "Download newer", "Keep my copy", {
                    attachmentStorageManager.deleteAttachment(item)
                    presenter.updateAttachmentDownloadProgress(0, -1)
                    downloadAttachment(item, openWhenDone = false)
                }, {}
            )
            AttachmentOpenCheck.Action.RECORD_BASE_AND_OPEN -> {
                recordBase(item, localMd5)
                presenter.makeToastAlert("Already on this device.")
            }
            AttachmentOpenCheck.Action.OPEN -> presenter.makeToastAlert("Already on this device.")
        }
    }

    override fun downloadAttachment(item: Item) = downloadAttachment(item, openWhenDone = true)

    fun downloadAttachment(item: Item, openWhenDone: Boolean) {
        if (isDownloading) {
            Log.d("zotero", "not downloading ${item.getTitle()} because i am already downloading.")
            return
        }
        isDownloading = true

        zoteroAPI.downloadItemRx(item, state.currentGroup.id, context)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : Observer<DownloadProgress> {
                var receivedMetadata = false

                override fun onComplete() {
                    isDownloading = false
                    presenter.finishDownloadingAttachment()

                    if (zoteroDB.hasMd5Key(item) && !attachmentStorageManager.validateMd5ForItem(
                            item, zoteroDB.getMd5Key(item)
                        )
                    ) {
                        Log.d("zotero", "md5 error on attachment ${zoteroDB.getMd5Key(item)}")
                        presenter.createErrorAlert(
                            "MD5 Verification Error",
                            "The downloaded file does not match the accompanying md5 checksum.",
                            {})
                        attachmentStorageManager.deleteAttachment(item)
                        return
                    } else {
                        recordBase(item, attachmentStorageManager.calculateMd5(item))
                        refreshPendingEdits()
                        if (openWhenDone) {
                            openPDF(item)
                        } else {
                            presenter.makeToastAlert("Downloaded ${item.data["filename"] ?: item.getTitle()}.")
                        }
                    }
                }

                override fun onSubscribe(d: Disposable) {
                    downloadDisposable = d

                }

                override fun onNext(t: DownloadProgress) {
                    presenter.updateAttachmentDownloadProgress(t.progress, t.total)
                    if (!receivedMetadata && t.metadataHash != "") {
                        receivedMetadata = true
                        zoteroDB.updateAttachmentMetadata(
                            item.itemKey,
                            t.metadataHash,
                            t.mtime,
                            if (preferences.isWebDAVEnabled()) {
                                AttachmentInfo.WEBDAV
                            } else {
                                AttachmentInfo.ZOTEROAPI
                            }
                        ).subscribeOn(Schedulers.io()).subscribe()
                    }
                }

                override fun onError(e: Throwable) {
                    Log.e("zotero", "got error ${e}")
                    if (e is ZoteroNotFoundException) {
                        presenter.attachmentDownloadError(
                            "The file does not exist on the Zotero server."
                        )
                    } else {
                        presenter.attachmentDownloadError(
                            "Error Message: ${e.message}"
                        )
                    }
                    isDownloading = false
                }

            })
    }


    override fun cancelAttachmentDownload() {
        Log.d("zotero", "cancelling download")
        this.isDownloading = false
        downloadDisposable?.dispose()
    }

    var isDownloading: Boolean = false
    var currentlyDownloadingAttachment: Item? = null

    override fun getUnfiledItems(): List<Item> {
        if (!zoteroDB.isPopulated()) {
            Log.e("zotero", "error zoteroDB not populated!")
            return LinkedList()
        }
        return filterItems(zoteroDB.getItemsWithoutCollection())
    }

    override fun createNote(note: Note) {
        if (state.isUsingGroup()) {
            presenter.makeToastAlert("Sorry, this isn't supported in shared collections.")
            return
        }
        zoteroAPI.uploadNote(note)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : CompletableObserver {
                override fun onComplete() {
                    presenter.hideBasicSyncAnimation()
                    syncManager.startItemsSync(db = zoteroDB)
                    presenter.makeToastAlert("Successfully created your note")
                }

                override fun onSubscribe(d: Disposable) {
                    presenter.showBasicSyncAnimation()
                }

                override fun onError(e: Throwable) {
                    presenter.createErrorAlert(
                        "Error creating note",
                        "An error occurred while trying to create your note. Message: $e"
                    ) {}
                }
            })


    }

    override fun modifyNote(note: Note) {
        if (state.isUsingGroup()) {
            presenter.makeToastAlert("Sorry, this isn't supported in shared collections.")
            return
        }
        zoteroAPI.modifyNote(note, zoteroDB.getLibraryVersion())
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : CompletableObserver {
                override fun onComplete() {
                    presenter.hideBasicSyncAnimation()
                    syncManager.startItemsSync(db = zoteroDB)
                    presenter.makeToastAlert("Successfully modified your note")
                }

                override fun onSubscribe(d: Disposable) {
                    presenter.showBasicSyncAnimation()
                }

                override fun onError(e: Throwable) {
                    if (e is ItemLockedException) {
                        presenter.createErrorAlert(
                            "Error modifying note",
                            "The note you are editing has been locked or you do not have permission to change it."
                        ) {}
                    } else if (e is ItemChangedSinceException) {
                        presenter.createErrorAlert(
                            "Error modifying note",
                            "The version on Zotero's servers is newer than your local copy. Please refresh your library."
                        ) {}

                    } else {
                        presenter.createErrorAlert(
                            "Error modifying note",
                            "An error occurred while trying to create your note. Message: $e"
                        ) {}
                    }
                    presenter.hideBasicSyncAnimation()
                }
            })
    }

    override fun deleteNote(note: Note) {
        if (state.isUsingGroup()) {
            presenter.makeToastAlert("Sorry, this isn't supported in shared collections.")
            return
        }
        zoteroAPI.deleteItem(note.key, note.version, object : DeleteItemListener {
            override fun success() {
                presenter.makeToastAlert("Successfully deleted your note.")
                zoteroDB.deleteItem(note.key)
                presenter.refreshItemView()
            }

            override fun failedItemLocked() {
                presenter.createErrorAlert(
                    "Error Deleting Note", "The item is locked " +
                            "and you do not have permission to delete this note."
                ) {}
            }

            override fun failedItemChangedSince() {
                presenter.createErrorAlert(
                    "Error Deleting Note",
                    "Your local copy of this note is out of date. " +
                            "Please refresh your library to delete this note."
                ) {}
            }

            override fun failed(code: Int) {
                presenter.createErrorAlert(
                    "Error Deleting Note", "There was an error " +
                            "deleting your note. The server responded : $code"
                ) {}
            }
        })
    }

    override fun deleteAttachment(item: Item) {
        zoteroAPI.deleteItem(item.itemKey, item.getVersion(), object : DeleteItemListener {
            override fun success() {
                presenter.makeToastAlert("Successfully deleted your attachment.")
                zoteroDB.deleteItem(item.itemKey)
                presenter.refreshItemView()
            }

            override fun failedItemLocked() {
                presenter.createErrorAlert(
                    "Error Deleting Attachment", "The item is locked " +
                            "and you do not have permission to delete this attachment."
                ) {}
            }

            override fun failedItemChangedSince() {
                presenter.createErrorAlert(
                    "Error Deleting Attachment",
                    "Your local copy of this note is out of date. " +
                            "Please refresh your library to delete this attachment."
                ) {}
            }

            override fun failed(code: Int) {
                presenter.createErrorAlert(
                    "Error Deleting Attachment", "There was an error " +
                            "deleting your attachment. The server responded : $code"
                ) {}
            }
        })
    }

    fun displayGroupsOnUI() {
        val groups = zoteroDatabase.getGroups()
        groups.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .unsubscribeOn(Schedulers.io())
            .subscribe(object : MaybeObserver<List<GroupInfo>> {
                override fun onSuccess(groupInfo: List<GroupInfo>) {
                    Log.d("zotero", "completed get group info.")
                    this@LibraryActivityModel.groups = groupInfo
                    presenter.displayGroupsOnActionBar(groupInfo)
                }

                override fun onComplete() {
                    Log.d("zotero", "User has no groups.")
                }

                override fun onSubscribe(d: Disposable) {
                }

                override fun onError(e: Throwable) {
                    Log.e("zotero", "error loading groups.")
                    presenter.createErrorAlert(
                        "Error loading group data",
                        "Message: ${e.message}",
                        {})
                }

            })
    }

    fun loadGroups() {
        /* This method loads a list of groups. It does not deal with getting items or catalogs. */
        if (!preferences.isGroupLibrarySyncEnabled()) {
            Log.d("zotero", "group library sync disabled in settings; not loading groups")
            return
        }

        val groupsObserver = zoteroAPI.getGroupInfo()
        groupsObserver.subscribeOn(Schedulers.io())
            .observeOn(Schedulers.io())
            .unsubscribeOn(Schedulers.io())
            .subscribe(object : Observer<List<GroupInfo>> {
                override fun onComplete() {
                    Log.d("zotero", "completed getting group info")
                    displayGroupsOnUI()
                }

                override fun onSubscribe(d: Disposable) {
                    Log.d("zotero", "subscribed to group info")
                }

                override fun onNext(groupInfo: List<GroupInfo>) {
                    groupInfo.forEach {
                        val status = zoteroDatabase.addGroup(it)
                        status.blockingAwait() // wait until the db add is finished.
                    }
                }

                override fun onError(e: Throwable) {
                }

            })
    }

    /** Counts edited attachments off the main thread and shows the number in the toolbar. */
    fun refreshPendingEdits() {
        Single.fromCallable { findEditedAttachments().size }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ presenter.showPendingEdits(it) }, { e ->
                Log.e("zotero", "counting edited attachments failed: $e")
            })
    }

    /** "Upload edited attachments": lists every edited attachment to pick which to upload. */
    fun chooseEditedAttachmentsToUpload() {
        if (state.isUsingGroup()) {
            presenter.makeToastAlert("Uploading is only supported in My Library.")
            return
        }
        if (!preferences.isAttachmentUploadingEnabled()) {
            presenter.makeToastAlert("Attachment uploading is turned off in settings.")
            return
        }
        Single.fromCallable {
            findEditedAttachments().map { Pair(it, uploadChoiceLabel(it)) }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ changed ->
                if (changed.isEmpty()) {
                    presenter.makeToastAlert("No edited attachments to upload.")
                } else {
                    askToUploadAttachments(changed)
                }
            }, { e ->
                Log.e("zotero", "looking for edited attachments failed: $e")
            })
    }

    private fun uploadChoiceLabel(attachment: Item): String {
        val name = attachment.data["filename"] ?: attachment.itemKey
        val size = runCatching {
            attachmentStorageManager.getFileSize(attachmentStorageManager.getAttachmentUri(attachment))
        }.getOrDefault(0L)
        return "$name (${size / 1000} KB)"
    }

    /** Lists every edited attachment, all ticked; unticked ones stay edited for a later sync. */
    private fun askToUploadAttachments(changed: List<Pair<Item, String>>) {
        presenter.chooseAttachmentsToUpload(changed.map { it.second }) { chosen ->
            uploadAttachments(chosen.map { changed[it].first })
        }
    }

    override fun uploadAttachment(attachment: Item) {
        uploadAttachments(listOf(attachment))
    }

    private var uploadDisposable: Disposable? = null

    /**
     * Uploads [attachments] one after another and reports once at the end. A file that is
     * refused (changed elsewhere, damaged) or fails doesn't stop the others; it stays in
     * RecentlyOpenedAttachment, so it is offered again.
     */
    private fun isUploading() = uploadDisposable?.isDisposed == false

    fun uploadAttachments(attachments: List<Item>, onDone: (() -> Unit)? = null) {
        if (attachments.isEmpty()) return
        if (isUploading()) {
            presenter.makeToastAlert("Already uploading attachments.")
            return
        }
        val total = attachments.size
        uploadDisposable = Observable.fromIterable(attachments.withIndex())
            .concatMapSingle { (index, attachment) ->
                val name = attachment.data["filename"] ?: attachment.itemKey
                Completable.fromAction {
                    presenter.startUploadingAttachmentProgress(attachment, index + 1, total)
                }.subscribeOn(AndroidSchedulers.mainThread())
                    .andThen(uploadOne(attachment).subscribeOn(Schedulers.io()))
                    .toSingleDefault(AttachmentUploadBatch.Outcome(name))
                    .onErrorReturn { e ->
                        Log.e("zotero", "upload of ${attachment.itemKey} failed: $e")
                        AttachmentUploadBatch.outcome(name, e)
                    }
            }
            .toList()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ outcomes ->
                presenter.stopUploadingAttachmentProgress()
                val summary = AttachmentUploadBatch.summary(outcomes)
                if (summary.quiet) {
                    presenter.makeToastAlert(summary.message)
                } else {
                    presenter.createErrorAlert(summary.title, summary.message, {})
                }
                refreshPendingEdits()
                onDone?.invoke()
            }, { e ->
                Log.e("zotero", "upload batch failed: $e")
                presenter.stopUploadingAttachmentProgress()
                presenter.createErrorAlert("Error uploading attachments", e.toString(), {})
                refreshPendingEdits()
                onDone?.invoke()
            })
    }

    /** Uploads one edited attachment and records it as synced. Subscribe on io. */
    private fun uploadOne(attachment: Item): Completable = Completable.defer {
        // throws FileNotFoundException if the copy is gone
        val md5Key = attachmentStorageManager.calculateMd5(attachment)
        fileCheckProblem(attachment)?.let { throw DamagedAttachmentException(it) }
        var mtime = attachmentStorageManager.getMtime(attachment)
        if (mtime < attachment.getMtime()) {
            Log.e("zotero", "for some reason our mtime is older than the original???")
            mtime = attachment.getMtime()
        }
        val webdav = preferences.isWebDAVEnabled()
        val upload = if (webdav) {
            uploadWithWebdav(attachment, md5Key, mtime)
        } else {
            zoteroAPI.updateAttachment(attachment).onErrorResumeNext { e ->
                if (e is AlreadyUploadedException) Completable.complete() else Completable.error(e)
            }
        }
        upload.andThen(Completable.fromAction {
            recordBase(attachment, md5Key)
            zoteroDB.updateAttachmentMetadata(
                attachment.itemKey,
                md5Key,
                attachmentStorageManager.getMtime(attachment),
                if (webdav) AttachmentInfo.WEBDAV else AttachmentInfo.ZOTEROAPI
            ).blockingAwait()
        })
    }

    private fun uploadWithWebdav(attachment: Item, md5Key: String, mtime: Long): Completable =
        // Check for remote changes BEFORE touching the WebDAV server: the upload
        // replaces KEY.zip/.prop outright, and only the later metadata patch is
        // version-checked by Zotero. See AttachmentUploadPreflight.
        Single.fromCallable {
            val (serverVersion, serverMd5) =
                zoteroAPI.getAttachmentFileState(attachment.itemKey).blockingGet()
            val remoteProp = zoteroAPI.getWebdavRemoteProp(attachment, context)
            // the version this device's edits are based on; for copies downloaded
            // before bases were recorded, the md5 as of the last sync (weaker)
            val baseMd5 = attachmentBase(attachment) ?: attachment.getMd5Key()
            AttachmentUploadPreflight.conflictReason(
                baseMd5, serverMd5, remoteProp?.hash
            )?.let { throw RemoteAttachmentChangedException(it) }
            Pair(serverVersion, remoteProp)
        }.flatMapCompletable { (serverVersion, remoteProp) ->
            // the .prop's ETag makes the WebDAV replace conditional; see WebdavUploader
            zoteroAPI.uploadAttachmentWithWebdav(
                attachment, context, remoteProp, md5Key, mtime
            ).andThen(
                Completable.fromAction {
                    val modificationJsonObject = JsonObject()
                    modificationJsonObject.addProperty("md5", md5Key)
                    modificationJsonObject.addProperty("mtime", mtime)
                    // the version just checked, so attachment edits that didn't touch
                    // the file (e.g. a tag) don't fail the patch after the upload
                    zoteroAPI.patchItem(attachment, modificationJsonObject, serverVersion)
                        .blockingAwait()
                }
            )
        }

    private val editFinder = EditedAttachmentFinder(
        stampOf = { key ->
            zoteroDB.getItemWithKey(key)?.let { item ->
                runCatching {
                    Pair(
                        attachmentStorageManager.getFileSize(item).getOrThrow(),
                        attachmentStorageManager.getMtime(item)
                    )
                }.getOrNull()
            }
        },
        md5Of = { key ->
            attachmentStorageManager.calculateMd5(
                zoteroDB.getItemWithKey(key) ?: throw FileNotFoundException(key)
            )
        }
    )

    /**
     * Attachments in My Library edited on this device and not uploaded yet; see
     * EditedAttachmentFinder. Hashes files whose size or mtime changed, so call it off
     * the main thread.
     */
    fun findEditedAttachments(): List<Item> {
        if (state.isUsingGroup() || !preferences.isAttachmentUploadingEnabled() ||
            !zoteroDB.isPopulated()
        ) return emptyList()
        val bases = HashMap<String, String>()
        for (key in attachmentStorageManager.listDownloadedItemKeys()) {
            if (zoteroDB.getItemWithKey(key) == null) continue
            zoteroDatabase.getAttachmentBase(key, zoteroDB.groupID)?.let { bases[key] = it }
        }
        return editFinder.edited(bases).mapNotNull { zoteroDB.getItemWithKey(it) }
    }

    /** Where [item]'s file stands on this device; worked out off the main thread. */
    fun describeAttachment(item: Item, onState: (AttachmentLocalState) -> Unit) {
        Single.fromCallable {
            AttachmentLocalState.of(
                editFinder.currentMd5(item.itemKey), attachmentBase(item), item.getMd5Key()
            )
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ onState(it) }, { e -> Log.e("zotero", "describing ${item.itemKey} failed: $e") })
    }

    /** md5 of the file this device last downloaded/uploaded for [item]; see AttachmentBase. */
    private fun attachmentBase(item: Item): String? =
        zoteroDatabase.getAttachmentBase(item.itemKey, zoteroDB.groupID)

    private fun recordBase(item: Item, md5: String) {
        if (md5.isNotEmpty()) zoteroDatabase.setAttachmentBase(item.itemKey, zoteroDB.groupID, md5)
    }

    /**
     * Gives downloaded copies from before AttachmentBase existed a base: first from
     * AttachmentInfo while it still has a row, then, for files still without one, Zotero's
     * md5 if the file provably matches it. A copy that matches neither (edited before the
     * upgrade, and its AttachmentInfo already wiped by a sync) stays without a base and
     * falls back to the weaker check. Only files lacking a base are hashed.
     */
    private fun adoptAttachmentBases(db: ZoteroDB) {
        zoteroDatabase.backfillAttachmentBases()
        val withBase = zoteroDatabase.attachmentKeysWithBase(db.groupID)
        val onDisk = attachmentStorageManager.listDownloadedItemKeys() - withBase
        if (onDisk.isEmpty()) return
        db.items?.filter { it.itemType == Item.ATTACHMENT_TYPE && it.itemKey.uppercase() in onDisk }
            ?.forEach { item ->
                val zoteroMd5 = item.getMd5Key()
                if (zoteroMd5.isEmpty()) return@forEach
                val local = runCatching { attachmentStorageManager.calculateMd5(item) }.getOrNull()
                if (local != null && local.equals(zoteroMd5, ignoreCase = true)) {
                    zoteroDatabase.setAttachmentBase(item.itemKey, db.groupID, local)
                }
            }
    }

    /** Reads the first and last few bytes and runs [AttachmentFileCheck]. */
    private fun fileCheckProblem(attachment: Item): String? {
        val window = AttachmentFileCheck.WINDOW
        val size = attachmentStorageManager.getFileSize(
            attachmentStorageManager.getAttachmentUri(attachment)
        )
        fun readUpTo(stream: java.io.InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = stream.read(buf, read, n - read)
                if (r < 0) break
                read += r
            }
            return buf.copyOf(read)
        }
        val head = attachmentStorageManager.getItemInputStream(attachment).use { readUpTo(it, window) }
        val tail = attachmentStorageManager.getItemInputStream(attachment).use { stream ->
            var toSkip = maxOf(0L, size - window)
            while (toSkip > 0) {
                val skipped = stream.skip(toSkip)
                if (skipped <= 0) break
                toSkip -= skipped
            }
            readUpTo(stream, window)
        }
        val isPdf = attachment.data["contentType"] == "application/pdf" ||
                attachmentStorageManager.getFilenameForItem(attachment).endsWith(".pdf", ignoreCase = true)
        return AttachmentFileCheck.problem(head, tail, size, isPdf)
    }


    override fun startGroupSync(group: GroupInfo, refresh: Boolean) {
        if (syncManager.isSyncing()) {
            Log.e("zotero", "cannot sync groups, still in a sync")
            presenter.makeToastAlert("Cannot open groups yet, still syncing library.")
            return
        }

        val db = zoteroGroupDB.getGroup(group.id)
        if (!refresh && db.isPopulated()) {
            // already synced and loaded.
            finishLibrarySync(db)
            return
        }

        syncManager.startCompleteSync(db, false)
    }

    override fun usePersonalLibrary() {
        this.zoteroDBPicker.stopGroup()
    }

    fun getCurrentCollection(): String {
        return navigator.currentCollection()
    }

    fun setCurrentCollection(collectionName: String, usePersonalLibrary: Boolean = true) {
        navigator.setCollection(collectionName, usePersonalLibrary)
    }

    fun checkAttachmentStorageAccess() {
        try {
            if (attachmentStorageManager.testStorage() == false) {
                throw Exception()
            }
        } catch (e: Exception) {
            Log.e("zotero", "error testing storage. ${e}")
            presenter.createErrorAlert(
                "Permission Error",
                "There was an error accessing your zotero attachment location. Please reconfigure in settings.",
                {})
            preferences.useExternalCache()
        }
    }

    fun getTrashedItems(): List<Item> {
        return zoteroDB.getTrashItems()
    }

    fun getCollectionFromKey(collectionKey: String): ZoteroCollection? {
        return zoteroDB.getCollectionById(collectionKey)
    }

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
        if (!zoteroDB.isPopulated())
            finishLibrarySync(zoteroDB) {
                onFinish()
            }
    }

    override fun isFirstSync(): Boolean {
        return zoteroDB.getLastSyncedTimestamp() == 0L
    }

    override fun finishLibrarySync(db: ZoteroDB, onFinish: () -> Unit) {
        /* This method is the endpoint for zotero api syncs,
        The role of this function is to load the library into memory and then
        trigger a UI update.
        * This will be called from the SyncManager. */
        Log.d(TAG, "finishLibrarySync() called - starting final stage (loading from database)")


        val loadCollections = db.loadCollectionsFromDatabase().doOnError { e ->
            Log.e(TAG, "loading collections from db got error $e")
            db.collections = LinkedList()
        }.doOnComplete {
            Log.d(TAG, "loadCollectionsFromDatabase completed - loaded ${db.collections?.size ?: 0} collections")
        }

        val loadItems = db.loadItemsFromDatabase().doOnError { e ->
            Log.e(TAG, "loading Items from db got error $e")
            db.items = LinkedList()
        }.doOnComplete {
            Log.d(TAG, "loadItemsFromDatabase completed - loaded ${db.items?.size ?: 0} items")
        }

        loadCollections
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .andThen(loadItems)
            .andThen(db.loadTrashItemsFromDB())
            // before any sync can change Zotero's md5s (startup loads locally first)
            .andThen(Completable.fromAction { adoptAttachmentBases(db) }
                .subscribeOn(Schedulers.io())
                .doOnError { Log.e(TAG, "adopting attachment bases failed: $it") }
                .onErrorComplete())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnComplete {
                presenter.hideBasicSyncAnimation()
                if (db.groupID == GroupInfo.NO_GROUP_ID) {
                    presenter.receiveCollections(getCollections())
                    if (state.currentCollection == "unset") {
                        presenter.setCollection("all")
                    } else {
                        presenter.redisplayItems()
                    }
                } else {
                    zoteroDBPicker.groupId = db.groupID
                    val newState = LibraryModelState().apply {
                        this.currentCollection = "group_all"
                        this.currentGroup = groups?.filter { it.id == db.groupID }?.firstOrNull()!!
                    }
                    this.states.add(newState)
                    presenter.redisplayItems()
                }
                if (db.items?.size == 0) {
                    // incase there was an error, i don't want users to be stuck with an empty library.
                    db.setItemsVersion(0)
                }
                this.refreshPendingEdits()
            }.onErrorComplete {
                presenter.createErrorAlert("error loading library", "got error message ${it}", {})
                presenter.hideBasicSyncAnimation()
                true
            }
            .doAfterTerminate {
                onFinish()
            }.subscribe()
    }

    private var doubleBackToExitPressedOnce = false
    override fun loadPriorState() {
        // check to see if we're at the root level, the first state is a junk state, so we
        // check at 2.
        if (states.size <= 2) {
            if (doubleBackToExitPressedOnce) {
                presenter.finishLibrary()
                return
            }

            this.doubleBackToExitPressedOnce = true
            presenter.makeToastAlert("Please press BACK again to exit")
            Handler().postDelayed(Runnable { doubleBackToExitPressedOnce = false }, 2000)
            return
        }
        val oldState = states.peek()
        states.pop()
        if (oldState.currentGroup != state.currentGroup) {
            if (state.isUsingGroup()) {
                presenter.openGroup(state.currentGroup.name)
            } else {
                // we are returning to the user's collection.
                this.usePersonalLibrary()
                presenter.setCollection(state.currentCollection)
            }
        } else if (oldState.currentCollection != state.currentCollection ||
            oldState.filterText != state.filterText
        ) {
            // redraws the state we returned to, filter (e.g. a tag view) and title included
            presenter.redisplayItems()
        } else {
            Log.e("zotero", "error unable to determine state differences!!")
        }
    }

    override fun getAllTags(): List<Pair<String, Int>> {
        return LibraryItemFilter.tagCounts(zoteroDB.items)
    }

    override fun getItemsForTag(tagName: String): List<Item> {
        return zoteroDB.getItemsForTag(tagName)
    }

    override fun deleteLocalAttachment(attachment: Item) {
        val delete = {
            attachmentStorageManager.deleteAttachment(attachment)
            refreshPendingEdits()
            presenter.makeToastAlert("Deleted the copy of ${attachment.getTitle()} on this device.")
        }
        val base = attachmentBase(attachment)
        val edited = base != null &&
                editFinder.currentMd5(attachment.itemKey)?.equals(base, ignoreCase = true) == false
        if (edited) {
            presenter.createYesNoPrompt(
                "Delete your edits?",
                "This copy has changes that haven't been uploaded yet. Deleting it loses them.",
                "Delete", "Keep", delete, {}
            )
        } else {
            delete()
        }
    }

    init {
        val appContext = context.applicationContext
        val hiltEntryPoint = EntryPointAccessors.fromApplication(appContext, SingletonComponentsEntryPoint::class.java)

        zoteroDatabase = hiltEntryPoint.getZoteroDatabase()
        attachmentStorageManager = hiltEntryPoint.getAttachmentStorageManager()
        preferences = hiltEntryPoint.getPreferences()

        val auth = AuthenticationStorage(context)
        // the initial (root) library state is seeded by LibraryNavigator's init.

        if (auth.hasCredentials()) {
            zoteroAPI = ZoteroAPI(
                auth.getUserKey(),
                auth.getUserID(),
                auth.getUsername(),
                attachmentStorageManager,
                preferences
            )
        } else {
            presenter.createErrorAlert(
                "Error with stored API",
                "The API Key we have stored in the application is invalid!" +
                        "Please re-authenticate the application"
            ) { presenter.finishLibrary() }
            auth.destroyCredentials()
            zoteroDB.clearItemsVersion()
        }

        syncManager = SyncManager(zoteroAPI, preferences, zoteroDatabase, this)
        checkAttachmentStorageAccess()
    }

    override fun startSyncAnimation(useSmallAnimation: Boolean) {
        if (useSmallAnimation) {
            presenter.showBasicSyncAnimation()
        } else {
            presenter.showLibraryLoadingAnimation()
        }
    }

    override fun stopSyncAnimation() {
        presenter.hideLibraryLoadingAnimation()
    }

    override fun createErrorAlert(title: String, message: String, onClick: () -> Unit) {
        presenter.createErrorAlert(title, message, onClick)
    }

    override fun setSyncProgress(progress: Int, total: Int) {
        presenter.updateLibraryRefreshProgress(progress, total, "")
    }

    override fun makeToastAlert(message: String) {
        presenter.makeToastAlert(message)
    }
}