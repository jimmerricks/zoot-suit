package com.mickstarify.zooforzotero.LibraryActivity

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.SyncSetup.domain.AuthenticationStorageImpl
import io.reactivex.rxjava3.core.Single
import com.mickstarify.zooforzotero.ZoteroAPI.GROUP_LIST_LIMIT
import com.mickstarify.zooforzotero.ZoteroAPI.AttachmentUploadBatch
import com.mickstarify.zooforzotero.ZoteroAPI.AttachmentUploader
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
import com.mickstarify.zooforzotero.ZoteroStorage.EditedAttachments
import com.mickstarify.zooforzotero.ZoteroStorage.Database.AttachmentInfo
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEdit
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditKind
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditStore
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEdits
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEditUploader
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Database.ZoteroDatabase
import com.mickstarify.zooforzotero.ZoteroStorage.ZoteroDB.ZoteroDB
import com.mickstarify.zooforzotero.di.SingletonComponentsEntryPoint
import dagger.hilt.android.EntryPointAccessors
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.CompletableObserver
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.MaybeObserver
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Observer
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.FileNotFoundException
import java.util.LinkedList
import java.util.Optional
import java.util.concurrent.Executors
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

    private var groups: List<GroupInfo>? = null

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
                if (isUploading()) {
                    pull()
                } else {
                    Single.fromCallable { findEditedAttachments() }
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe({ edited ->
                            if (edited.isEmpty()) pull() else uploadAttachments(edited, onDone = pull)
                        }, { e ->
                            Log.e("zotero", "looking for edited attachments failed: $e")
                            pull()
                        })
                }
            }
            uploadItemEdits(onDone = attachmentsThenPull)
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

    /**
     * Answers the list's queries from the database, for the open library (read at each
     * one). Lazy: zoteroDatabase is set in init, after the properties.
     */
    private val itemSource: LibraryItemSource by lazy {
        RoomItemSource(
            database = zoteroDatabase,
            groupId = { zoteroDB.groupID },
            collections = { getCollections() },
            deviceState = { DeviceState(onDeviceKeys, editedKeys, itemEditKeys) },
            searchFields = { preferences.getSearchFields() },
        )
    }

    // one thread: lists are built in the order asked for, never two at once
    private val listScheduler = Schedulers.from(Executors.newSingleThreadExecutor())

    // the latest list asked for; an older one that finishes later is dropped
    private var listGeneration = 0

    /**
     * Builds the list for [place] in the open library, after [librarySearch] and
     * [libraryFilter] (Trash and My Publications are shown whole), in the library's sort
     * order, off the main thread, with its first page of rows; then sets
     * [lastFilteredCount] and calls [onResult] on the main thread, unless another list was
     * asked for meanwhile. Later pages are read as the list scrolls ([ItemPages]). Call on
     * the main thread.
     */
    fun listItems(place: LibraryPlace, onResult: (ItemPages) -> Unit) {
        val groupId = state.currentGroup.id
        val query = LibraryQuery(
            place = place,
            search = librarySearch,
            filter = libraryFilter,
            sort = preferences.getSortMethod(groupId),
            ascending = preferences.isSortedAscendingly(groupId),
            includeSubcollections = preferences.showSubcollectionItems(),
        )
        val generation = ++listGeneration
        val start = System.nanoTime()
        Single.fromCallable {
            val rows = itemSource.query(query)
            rows to rows.load(0, minOf(rows.size, ItemPages.PAGE_SIZE))
        }
            .subscribeOn(listScheduler)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (rows, firstPage) ->
                Log.d(TAG, "listItems($place) - ${rows.size} items in ${(System.nanoTime() - start) / 1_000_000} ms")
                if (generation != listGeneration) return@subscribe
                lastFilteredCount = if (place.isNarrowed) rows.size else null
                onResult(ItemPages(rows, firstPage, ::loadRows))
            }, { e ->
                Log.e(TAG, "building the list for $place failed: $e")
                if (generation == listGeneration) {
                    ui.createErrorAlert("Couldn't show the list", "Details: ${e.message ?: e}") {}
                }
            })
    }

    /**
     * The open library's list filter. Each library has its own. With "Keep the filter"
     * on, it is saved and stays when another collection opens; off (the default), it
     * clears then, and the app starts without one. See [clearOnCollectionChange].
     */
    var libraryFilter: LibraryFilter
        get() {
            clearOnCollectionChange()
            return savedFilters.getOrPut(state.currentGroup.id) {
                if (preferences.keepFilter()) preferences.getLibraryFilter(state.currentGroup.id) else LibraryFilter()
            }
        }
        set(value) {
            val id = state.currentGroup.id
            savedFilters[id] = value
            preferences.setLibraryFilter(id, value)
        }
    private val savedFilters = mutableMapOf<Int, LibraryFilter>() // by library (group id)

    /**
     * The open library's search, narrowing every list like [libraryFilter]; "" for none.
     * Each library has its own, kept or cleared like the filter ("Keep the search").
     */
    var librarySearch: String
        get() {
            clearOnCollectionChange()
            return searches.getOrPut(state.currentGroup.id) {
                if (preferences.keepSearch()) preferences.getLibrarySearch(state.currentGroup.id) else ""
            }
        }
        set(value) {
            val id = state.currentGroup.id
            searches[id] = value.trim()
            preferences.setLibrarySearch(id, value.trim())
        }
    private val searches = mutableMapOf<Int, String>() // by library (group id)

    /** The library and collection the search and filter were last read for. */
    private var shownCollection: Pair<Int, String>? = null

    /**
     * Clears the search and the filter, unless set to be kept, when the collection (or
     * the library) differs from the last one they were read for. Back
     * within the same collection keep them. Called by their getters, which every list,
     * dialog and subtitle reads after navigation has settled.
     */
    private fun clearOnCollectionChange() {
        val now = state.currentGroup.id to state.currentCollection
        val before = shownCollection
        shownCollection = now
        if (before == null || before == now) return
        val id = now.first
        if (!preferences.keepSearch()) {
            searches[id] = ""
            preferences.setLibrarySearch(id, "")
        }
        if (!preferences.keepFilter()) {
            savedFilters[id] = LibraryFilter()
            preferences.setLibraryFilter(id, LibraryFilter())
        }
    }

    /**
     * How many items the list shows, after search and filter; null if the current list
     * doesn't go through the filter (trash, My Publications). For the toolbar's count.
     */
    var lastFilteredCount: Int? = null
        private set

    /** Attachment keys whose file is on this device / has edits to sync; see refreshPendingEdits. */
    @Volatile private var onDeviceKeys: Set<String> = emptySet()
    @Volatile private var editedKeys: Set<String> = emptySet()

    /** Keys of My Library's items with metadata edits to sync; see refreshPendingEdits. */
    @Volatile private var itemEditKeys: Set<String> = emptySet()

    private val itemEdits by lazy { ItemEditStore(zoteroDatabase) }

    // lazy: zoteroDatabase is set in init, after the properties
    private val editedAttachments by lazy {
        EditedAttachments(zoteroDatabase, attachmentStorageManager) { zoteroDB.groupID }
    }

    private val uploader by lazy {
        AttachmentUploader(context, { zoteroAPI }, attachmentStorageManager, preferences, editedAttachments) { zoteroDB }
    }

    fun hasPendingEdits(item: Item) = !state.isUsingGroup() && item.itemKey in itemEditKeys

    /**
     * Adds ([add]) or removes [tag] on [item], on this device at once; Zotero gets it with
     * the next sync (refreshLibrary), offline or not. My Library only, like notes.
     */
    fun editTag(item: Item, tag: String, add: Boolean) {
        if (state.isUsingGroup()) {
            ui.makeToastAlert("Tags can be edited only in My Library, not in groups.")
            return
        }
        val edit = ItemEdit(if (add) ItemEditKind.ADD_TAG else ItemEditKind.REMOVE_TAG, tag)
        Single.fromCallable { itemEdits.record(GroupInfo.NO_GROUP_ID, item, edit) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ changed ->
                if (!changed) return@subscribe
                itemSource.invalidate() // its search holds the item's tags
                // after the count, so the dialog says whether edits are waiting
                refreshPendingEdits { ui.showEditedItem(item) }
            }, { e ->
                Log.e("zotero", "editing a tag of ${item.itemKey} failed: $e")
                ui.createErrorAlert("Couldn't change the tag", "Details: ${e.message ?: e}") {}
            })
    }

    /**
     * Adds a note with [text] (plain text, as typed) to [item], on this device at once;
     * Zotero gets it with the next sync, like a tag. My Library only.
     */
    fun addNote(item: Item, text: String) {
        if (state.isUsingGroup()) {
            ui.makeToastAlert("Notes can be added only in My Library, not in groups.")
            return
        }
        val html = ItemEdits.noteHtml(text) ?: run {
            ui.makeToastAlert("The note is empty, so it wasn't added.")
            return
        }
        Single.fromCallable { itemEdits.createNote(GroupInfo.NO_GROUP_ID, item, html) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ _ ->
                itemSource.invalidate() // "Has notes" and searching notes read the database
                refreshPendingEdits { ui.showEditedItem(item) }
            }, { e ->
                Log.e("zotero", "adding a note to ${item.itemKey} failed: $e")
                ui.createErrorAlert("Couldn't add the note", "Details: ${e.message ?: e}") {}
            })
    }

    /**
     * Applies [change] (an edit or a deletion) to a note made on this device that Zotero
     * doesn't have yet, off the main thread; then [onDone] on the main thread, if it was
     * such a note, or [notNew] if Zotero has it.
     */
    private fun changeNewNote(change: () -> ItemEditStore.NewNoteChange, notNew: () -> Unit, onDone: () -> Unit) {
        Single.fromCallable(change)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ result ->
                when (result) {
                    ItemEditStore.NewNoteChange.DONE -> {
                        itemSource.invalidate()
                        refreshPendingEdits { ui.refreshItemView() }
                        onDone()
                    }
                    ItemEditStore.NewNoteChange.NOT_NEW -> notNew()
                    ItemEditStore.NewNoteChange.UPLOADING ->
                        ui.makeToastAlert("The note is being synced. Try again in a moment.")
                }
            }, { e ->
                Log.e("zotero", "changing a new note failed: $e")
                ui.createErrorAlert("Couldn't change the note", "Details: ${e.message ?: e}") {}
            })
    }

    /** Uploads My Library's metadata edits, if there are any, says what went wrong, then [onDone]. */
    private fun uploadItemEdits(onDone: () -> Unit) {
        Single.fromCallable {
            if (itemEdits.pending(GroupInfo.NO_GROUP_ID).isEmpty()) ItemEditUploader.Result()
            else ItemEditUploader(zoteroAPI, itemEdits).uploadAll()
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ result ->
                // offline is said once by the sync that follows
                if (result.hasProblems) {
                    ui.createErrorAlert("Some edits weren't synced", itemEditProblems(result)) {}
                }
                refreshPendingEdits()
                onDone()
            }, { e ->
                Log.e("zotero", "uploading item edits failed: $e")
                onDone()
            })
    }

    private fun itemEditProblems(result: ItemEditUploader.Result): String = listOfNotNull(
        result.gone.takeIf { it > 0 }?.let {
            if (it == 1) "1 edited item was deleted in Zotero; its edits were dropped."
            else "$it edited items were deleted in Zotero; their edits were dropped."
        },
        result.orphanedNotes.takeIf { it > 0 }?.let {
            if (it == 1) "The item of 1 new note was deleted in Zotero; the note was dropped."
            else "The items of $it new notes were deleted in Zotero; the notes were dropped."
        },
        result.conflicts.takeIf { it > 0 }?.let {
            "$it item${if (it == 1) "" else "s"} had a field changed here and in Zotero; " +
                "that change stays on this device."
        },
        result.failures.takeIf { it.isNotEmpty() }?.let { failures ->
            "${failures.size} item${if (failures.size == 1) "" else "s"} couldn't be updated; " +
                "the edits stay on this device and are tried again at the next sync.\n\n" +
                "Details: ${failures.first().message ?: failures.first()}"
        },
    ).joinToString("\n\n")

    override fun getGroupById(groupId: Int): GroupInfo? {
        return this.groups?.firstOrNull { it.id == groupId }
    }

    fun getGroups(): List<GroupInfo> = groups ?: emptyList()

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

    override fun openPDF(attachment: Item) {
        /* This method creates an intent to open a PDF for Android. */

        run {
            try {
                val intent = attachmentStorageManager.openAttachment(attachment)
                ui.startActivity(intent)
            } catch (exception: ActivityNotFoundException) {
                ui.createYesNoPrompt("No app opens this file",
                    "Nothing on this device opens .${attachment.getFileExtension()} files. Install a PDF reader such as Xodo?",
                    "Get Xodo", "Cancel", onYesClick = {
                        try {
                            ui.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=com.xodo.pdf.reader")
                                )
                            )
                        } catch (e: ActivityNotFoundException) {
                            ui.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://play.google.com/store/apps/details?id=com.xodo.pdf.reader")
                                )
                            )
                        }
                    }, onNoClick = {})
            } catch (e: IllegalArgumentException) {
                ui.createErrorAlert("Couldn't open the file",
                    "Android refused access to the file. This is a known problem on Huawei " +
                            "devices: in Settings, set Attachment folder to Custom Path.",
                    {})
            } catch (e: FileNotFoundException) {
                ui.createErrorAlert("File not found",
                    "The file is missing from this device. Download it again.",
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
                ui.startActivity(intent)
            } else {
                ui.makeToastAlert("Linked file not found: ${item.data["path"]}")
            }
            return
        }
        ui.showLoadingAlertDialog("Opening…")
        // check to see if the attachment exists but is invalid
        val attachmentExists: Boolean
        try {
            attachmentExists = attachmentStorageManager.checkIfAttachmentExists(
                item,
                checkMd5 = false
            )
        } catch (e: Exception) {
            ui.hideLoadingAlertDialog()
            ui.makeToastAlert("File not found on this device.")
            return
        }
        if (attachmentExists) {
            ui.showLoadingAlertDialog("Checking for a newer version…")
            latestZoteroMd5(item)
                .observeOn(Schedulers.io())
                .map { latest -> Pair(latest, if (latest.isPresent) localCopyState(item) else null) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ (latest, local) ->
                    ui.hideLoadingAlertDialog()
                    if (!latest.isPresent || local == null) {
                        ui.makeToastAlert("Offline: opening the copy on this device.")
                        openPDF(item)
                    } else {
                        openLocalCopy(item, latest.get(), local)
                    }
                }, { e ->
                    ui.hideLoadingAlertDialog()
                    Log.e("zotero", "checking for a newer version failed: $e")
                    openPDF(item)
                })
        } else {
            ui.hideLoadingAlertDialog()
            ui.updateAttachmentDownloadProgress(0, -1)
            downloadAttachment(item)
        }

    }

    /**
     * The attachment's md5 on Zotero's server now ("" if it has no file), or null if the
     * server can't be asked: no network, a group library (the request is for My Library),
     * or no answer within a few seconds. One API request.
     */
    private fun latestZoteroMd5(item: Item): Single<Optional<String>> =
        if (state.isUsingGroup() || !hasNetwork()) {
            Single.just(Optional.empty())
        } else {
            zoteroAPI.getAttachmentFileState(item.itemKey)
                .map { (_, md5) -> Optional.of(md5 ?: "") }
                .timeout(5, TimeUnit.SECONDS)
                .onErrorReturn { e ->
                    Log.d("zotero", "no latest md5 for ${item.itemKey}: $e")
                    Optional.empty()
                }
                .subscribeOn(Schedulers.io())
        }

    /**
     * Whether Android reports a connected network. Checked before asking the server so
     * that offline opens are instant: a request on a dropped connection otherwise waits
     * for the timeout.
     */
    @Suppress("DEPRECATION") // activeNetworkInfo is the API 24 way
    private fun hasNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return cm.activeNetworkInfo?.isConnected == true
    }

    /** The local copy's md5 ("" if unreadable) and its base; hashes the file, so not on main. */
    private data class LocalCopyState(val md5: String, val base: String?)

    private fun localCopyState(item: Item) = LocalCopyState(
        runCatching { attachmentStorageManager.calculateMd5(item) }.getOrDefault(""),
        attachmentBase(item)
    )

    /** Opens the local copy, first replacing it if Zotero's [latestMd5] moved on. */
    private fun openLocalCopy(item: Item, latestMd5: String, local: LocalCopyState) {
        // Is the local copy based on Zotero's current version? See AttachmentOpenCheck.
        val localMd5 = local.md5
        val base = local.base
        when (AttachmentOpenCheck.decide(base, latestMd5, localMd5)) {
            AttachmentOpenCheck.Action.REDOWNLOAD -> {
                ui.makeToastAlert("Zotero has a newer version of this file. Downloading it.")
                ui.updateAttachmentDownloadProgress(0, -1)
                attachmentStorageManager.deleteAttachment(item)
                downloadAttachment(item)
                return
            }
            AttachmentOpenCheck.Action.ASK_EDITED_STALE -> {
                ui.createYesNoPrompt(
                    "Newer version in Zotero",
                    "This file changed in Zotero after you downloaded it, and your copy has " +
                            "edits of its own. Your copy can't be uploaded without overwriting the " +
                            "newer version.\n\nDownload the newer version (your edits are " +
                            "replaced), or open your copy and save your annotations as a new file.",
                    "Download newer", "Open my copy", {
                        ui.updateAttachmentDownloadProgress(0, -1)
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
        // A copy without a base can't be judged by AttachmentOpenCheck; offer Zotero's
        // version if it differs. With a base, a difference left here is this device's own
        // edit waiting to be uploaded, which "redownload" would throw away.
        if (base == null && latestMd5.isNotEmpty() && preferences.shouldCheckMd5SumBeforeOpening() &&
            !localMd5.equals(latestMd5, ignoreCase = true)
        ) {
            ui.createYesNoPrompt(
                "Different from Zotero's copy",
                "The file on this device differs from the one in Zotero. Download Zotero's version?",
                "Download", "Open mine", {
                    ui.updateAttachmentDownloadProgress(0, -1)
                    attachmentStorageManager.deleteAttachment(item)
                    downloadAttachment(item)
                }, {
                    openPDF(item)
                }
            )
            return
        }
        openPDF(item)
    }

    /** Reads rows of a list on the list's thread, for [ItemPages]; null to it if that fails. */
    private fun loadRows(rows: LibraryRows, from: Int, to: Int, onLoaded: (List<Item?>?) -> Unit) {
        Single.fromCallable { rows.load(from, to) }
            .subscribeOn(listScheduler)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ onLoaded(it) }, { e ->
                Log.e(TAG, "reading rows $from-$to failed: $e")
                onLoaded(null)
            })
    }

    /** Prepares the search in the background (on the list's thread) while the search dialog is open. */
    fun warmSearchIndex() {
        if (!isLoaded()) return
        Completable.fromAction { itemSource.warmSearch() }
            .subscribeOn(listScheduler)
            .subscribe({}, { e -> Log.e(TAG, "preparing the search failed: $e") })
    }

    var downloadDisposable: Disposable? = null

    /**
     * Puts [item]'s file on the device without opening it. A copy that's already there is
     * checked against Zotero's current version (when online) and kept, unless it's an
     * unedited copy of an older version (replaced) or an edited copy of one (asks, as
     * opening does).
     */
    fun downloadAttachmentOnly(item: Item) {
        if (item.data["linkMode"] == "linked_file" || item.data["linkMode"] == "linked_url") {
            ui.makeToastAlert("Linked attachments aren't stored in Zotero.")
            return
        }
        val exists = try {
            attachmentStorageManager.checkIfAttachmentExists(item, checkMd5 = false)
        } catch (e: Exception) {
            false
        }
        if (!exists) {
            ui.updateAttachmentDownloadProgress(0, -1)
            downloadAttachment(item, openWhenDone = false)
            return
        }
        latestZoteroMd5(item)
            .observeOn(Schedulers.io())
            .map { latest -> Pair(latest, if (latest.isPresent) localCopyState(item) else null) }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (latest, local) ->
                if (latest.isPresent && local != null) {
                    downloadIfOutdated(item, latest.get(), local)
                } else {
                    ui.makeToastAlert("Offline: keeping the copy on this device.")
                }
            }, { e -> Log.e("zotero", "checking for a newer version failed: $e") })
    }

    private fun downloadIfOutdated(item: Item, latestMd5: String, local: LocalCopyState) {
        val localMd5 = local.md5
        when (AttachmentOpenCheck.decide(local.base, latestMd5, localMd5)) {
            AttachmentOpenCheck.Action.REDOWNLOAD -> {
                attachmentStorageManager.deleteAttachment(item)
                ui.updateAttachmentDownloadProgress(0, -1)
                downloadAttachment(item, openWhenDone = false)
            }
            AttachmentOpenCheck.Action.ASK_EDITED_STALE -> ui.createYesNoPrompt(
                "Newer version in Zotero",
                "This file changed in Zotero after you downloaded it, and your copy has " +
                        "edits of its own. Downloading the newer version replaces your edits.",
                "Download newer", "Keep my copy", {
                    attachmentStorageManager.deleteAttachment(item)
                    ui.updateAttachmentDownloadProgress(0, -1)
                    downloadAttachment(item, openWhenDone = false)
                }, {}
            )
            AttachmentOpenCheck.Action.RECORD_BASE_AND_OPEN -> {
                recordBase(item, localMd5)
                ui.makeToastAlert("Already on this device.")
            }
            AttachmentOpenCheck.Action.OPEN -> ui.makeToastAlert("Already on this device.")
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
                    ui.finishDownloadingAttachment()
                    verifyDownload(item, openWhenDone)
                }

                override fun onSubscribe(d: Disposable) {
                    downloadDisposable = d

                }

                override fun onNext(t: DownloadProgress) {
                    ui.updateAttachmentDownloadProgress(t.progress, t.total)
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
                        ui.attachmentDownloadError(
                            "The file isn't in Zotero's storage (or on your WebDAV server)."
                        )
                    } else {
                        ui.attachmentDownloadError(
                            "Check your connection and try again.\n\nDetails: ${e.message}"
                        )
                    }
                    isDownloading = false
                }

            })
    }


    /**
     * Checks a finished download against Zotero's md5 and records it as the copy's base,
     * hashing the file once, off the main thread; then opens it or says it's there.
     */
    private fun verifyDownload(item: Item, openWhenDone: Boolean) {
        val expected = zoteroDB.getMd5Key(item)
        Single.fromCallable {
            val md5 = attachmentStorageManager.calculateMd5(item)
            val intact = expected.isEmpty() || md5.equals(expected, ignoreCase = true)
            if (intact) recordBase(item, md5)
            intact
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ intact ->
                if (!intact) {
                    Log.d("zotero", "md5 error on attachment $expected")
                    attachmentStorageManager.deleteAttachment(item)
                    ui.createErrorAlert(
                        "Download damaged",
                        "The downloaded file doesn't match Zotero's checksum, so it was " +
                                "deleted. Try again.",
                        {})
                    return@subscribe
                }
                refreshPendingEdits()
                if (openWhenDone) {
                    openPDF(item)
                } else {
                    ui.makeToastAlert("Downloaded ${item.data["filename"] ?: item.getTitle()}.")
                }
            }, { e ->
                Log.e("zotero", "checking the download of ${item.itemKey} failed: $e")
                ui.attachmentDownloadError("The downloaded file can't be read.\n\nDetails: ${e.message}")
            })
    }

    override fun cancelAttachmentDownload() {
        Log.d("zotero", "cancelling download")
        this.isDownloading = false
        downloadDisposable?.dispose()
    }

    var isDownloading: Boolean = false

    override fun modifyNote(note: Note) {
        if (state.isUsingGroup()) {
            ui.makeToastAlert("Notes can be edited only in My Library, not in groups.")
            return
        }
        changeNewNote({ itemEdits.editNewNote(GroupInfo.NO_GROUP_ID, note.key, note.note) },
            notNew = { uploadNoteEdit(note) }) { ui.makeToastAlert("Note saved.") }
    }

    private fun uploadNoteEdit(note: Note) {
        zoteroAPI.modifyNote(note, zoteroDB.getLibraryVersion())
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : CompletableObserver {
                override fun onComplete() {
                    ui.hideBasicSyncAnimation()
                    syncManager.startItemsSync(db = zoteroDB)
                    ui.makeToastAlert("Note saved.")
                }

                override fun onSubscribe(d: Disposable) {
                    ui.showBasicSyncAnimation()
                }

                override fun onError(e: Throwable) {
                    if (e is ItemLockedException) {
                        ui.createErrorAlert(
                            "Couldn't save the note",
                            "The note is locked, or your API key can't change it."
                        ) {}
                    } else if (e is ItemChangedSinceException) {
                        ui.createErrorAlert(
                            "Couldn't save the note",
                            "The note changed in Zotero since this device last synced. Pull down " +
                                    "to sync, then edit it again."
                        ) {}

                    } else {
                        ui.createErrorAlert(
                            "Couldn't save the note",
                            "Check your connection and try again.\n\nDetails: ${e.message ?: e}"
                        ) {}
                    }
                    ui.hideBasicSyncAnimation()
                }
            })
    }

    /** Deletes [note] from Zotero after asking: the API deletes it for good, not to the trash. */
    override fun deleteNote(note: Note) {
        if (state.isUsingGroup()) {
            ui.makeToastAlert("Notes can be deleted only in My Library, not in groups.")
            return
        }
        // version 0: made on this device, Zotero doesn't have it (yet)
        val message = if (note.version == 0) "It hasn't been synced yet, so it's only on this device."
        else "It's deleted from Zotero on all your devices, not moved to the trash. This can't be undone."
        ui.createYesNoPrompt("Delete this note?", message, "Delete", "Cancel", {
            changeNewNote({ itemEdits.deleteNewNote(GroupInfo.NO_GROUP_ID, note.key) },
                notNew = { deleteNoteNow(note) }) {
                selectedItem?.notes?.removeAll { it.key == note.key }
                ui.makeToastAlert("Note deleted.")
            }
        }, {})
    }

    private fun deleteNoteNow(note: Note) {
        zoteroAPI.deleteItem(note.key, note.version, object : DeleteItemListener {
            override fun success() {
                ui.makeToastAlert("Note deleted.")
                // the list reads the database; the next sync would remove it there too
                zoteroDatabase.deleteItem(note.key)
                    .subscribeOn(Schedulers.io())
                    .subscribe({ itemSource.invalidate() }, { Log.e(TAG, "deleting note ${note.key} locally failed: $it") })
                // the open item is a row read before: it still lists the note
                selectedItem?.notes?.removeAll { it.key == note.key }
                ui.refreshItemView()
            }

            override fun failedItemLocked() {
                ui.createErrorAlert(
                    "Couldn't delete the note",
                    "The note is locked, or your API key can't change it."
                ) {}
            }

            override fun failedItemChangedSince() {
                ui.createErrorAlert(
                    "Couldn't delete the note",
                    "The note changed in Zotero since this device last synced. Pull down to " +
                            "sync, then try again."
                ) {}
            }

            override fun failed(code: Int) {
                ui.createErrorAlert(
                    "Couldn't delete the note",
                    "Zotero answered with an error (HTTP $code). Try again later."
                ) {}
            }

            override fun failedNoConnection(e: Throwable) {
                ui.createErrorAlert(
                    "Couldn't delete the note",
                    "Couldn't reach Zotero. Check your connection and try again."
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
                    ui.showGroups()
                }

                override fun onComplete() {
                    Log.d("zotero", "User has no groups.")
                    this@LibraryActivityModel.groups = emptyList()
                    ui.showGroups()
                }

                override fun onSubscribe(d: Disposable) {
                }

                override fun onError(e: Throwable) {
                    Log.e("zotero", "error loading groups.")
                    ui.createErrorAlert(
                        "Couldn't load the group list",
                        "Details: ${e.message}",
                        {})
                }

            })
    }

    /**
     * Lists the groups in the sidebar: first the ones on this device (so they're there
     * offline), then Zotero's current list, which also drops groups the user has left.
     * It does not deal with getting items or collections.
     */
    fun loadGroups() {
        if (!preferences.isGroupLibrarySyncEnabled()) {
            Log.d("zotero", "group library sync disabled in settings; not loading groups")
            return
        }
        displayGroupsOnUI()

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
                    // a full page may not be the whole list; then forget nothing
                    if (groupInfo.size >= GROUP_LIST_LIMIT) return
                    val current = groupInfo.map { it.id }.toSet()
                    zoteroDatabase.getGroups().blockingGet()
                        ?.filter { it.id !in current }
                        ?.forEach { zoteroDatabase.deleteGroup(it) }
                }

                override fun onError(e: Throwable) {
                    // offline or refused: the groups on the device stay listed
                    Log.e("zotero", "could not refresh the group list: $e")
                }

            })
    }

    /**
     * Applies Settings → Group libraries at once. On: the groups are listed. Off: they
     * go from the sidebar, an open group closes (back to My Library) and Back forgets
     * places in groups. Groups only sync when opened, so nothing else depends on it.
     */
    fun setGroupLibrariesEnabled(enabled: Boolean) {
        if (enabled) {
            loadGroups()
            return
        }
        groups = emptyList()
        openingGroup = null
        openingGroupCollection = null
        if (navigator.leaveGroups()) {
            usePersonalLibrary()
            ui.receiveCollections(getCollections())
            ui.redisplayItems()
        }
        ui.showGroups()
    }

    /**
     * Works out, off the main thread, which attachments are on the device and which have
     * edits to sync; shows the edit count in the toolbar and both on the list's PDF icons,
     * and redraws the list if the filter depends on either and they changed. Then [onDone].
     */
    fun refreshPendingEdits(onDone: () -> Unit = {}) {
        Single.fromCallable {
            Triple(
                findOnDeviceAttachmentKeys(),
                findEditedAttachments().map { it.itemKey }.toSet(),
                itemEdits.editedItemKeys(GroupInfo.NO_GROUP_ID),
            )
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (onDevice, edited, itemsEdited) ->
                val changed = onDevice != onDeviceKeys || edited != editedKeys || itemsEdited != itemEditKeys
                onDeviceKeys = onDevice
                editedKeys = edited
                itemEditKeys = itemsEdited
                ui.showPendingEdits(edited.size + itemsEdited.size)
                ui.showAttachmentStates(onDevice, edited)
                if (changed && libraryFilter.needsDeviceState) ui.redisplayItems()
                onDone()
            }, { e ->
                Log.e("zotero", "checking attachments on the device failed: $e")
                onDone()
            })
    }

    /** Keys of attachments whose file is on this device (non-empty). */
    private fun findOnDeviceAttachmentKeys(): Set<String> =
        if (isLoaded()) editedAttachments.onDeviceKeys() else emptySet()

    /** "Upload edited attachments": lists every edited attachment to pick which to upload. */
    fun chooseEditedAttachmentsToUpload() {
        if (state.isUsingGroup()) {
            ui.makeToastAlert("Uploads work only in My Library, not in groups.")
            return
        }
        if (!preferences.isAttachmentUploadingEnabled()) {
            ui.makeToastAlert("Uploading is off. Turn on “Upload edited attachments” in Settings.")
            return
        }
        Single.fromCallable {
            findEditedAttachments().map { Pair(it, uploadChoiceLabel(it)) }
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ changed ->
                if (changed.isEmpty()) {
                    ui.makeToastAlert("No edited attachments to upload.")
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
        return "$name (${Formatter.formatShortFileSize(context, size)})"
    }

    /** Lists every edited attachment, all ticked; unticked ones stay edited for a later sync. */
    private fun askToUploadAttachments(changed: List<Pair<Item, String>>) {
        ui.chooseAttachmentsToUpload(changed.map { it.second }) { chosen ->
            uploadAttachments(chosen.map { changed[it].first })
        }
    }

    override fun uploadAttachment(attachment: Item) {
        uploadAttachments(listOf(attachment))
    }

    /** "Upload my changes" for one attachment: uploads it only if it differs from its base. */
    fun uploadChanges(item: Item) {
        Single.fromCallable {
            AttachmentLocalState.of(
                editedAttachments.currentMd5(item.itemKey), attachmentBase(item), zoteroDB.getMd5Key(item)
            )
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ state ->
                when (state) {
                    AttachmentLocalState.NOT_DOWNLOADED ->
                        ui.makeToastAlert("This file isn't on this device.")
                    AttachmentLocalState.ON_DEVICE, AttachmentLocalState.OUTDATED ->
                        if (attachmentBase(item) != null) {
                            ui.makeToastAlert("No changes to upload.")
                        } else {
                            uploadAttachments(listOf(item)) // can't tell; upload as asked
                        }
                    // CONFLICT goes through too: the preflight refuses it and says why
                    AttachmentLocalState.EDITED, AttachmentLocalState.CONFLICT ->
                        uploadAttachments(listOf(item))
                }
            }, { e -> Log.e("zotero", "checking ${item.itemKey} for changes failed: $e") })
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
            ui.makeToastAlert("Already uploading attachments.")
            return
        }
        val total = attachments.size
        uploadDisposable = Observable.fromIterable(attachments.withIndex())
            .concatMapSingle { (index, attachment) ->
                val name = attachment.data["filename"] ?: attachment.itemKey
                Completable.fromAction {
                    ui.startUploadingAttachmentProgress(attachment, index + 1, total)
                }.subscribeOn(AndroidSchedulers.mainThread())
                    .andThen(uploader.upload(attachment).subscribeOn(Schedulers.io()))
                    .toSingleDefault(AttachmentUploadBatch.Outcome(name))
                    .onErrorReturn { e ->
                        Log.e("zotero", "upload of ${attachment.itemKey} failed: $e")
                        AttachmentUploadBatch.outcome(name, e)
                    }
            }
            .toList()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ outcomes ->
                ui.stopUploadingAttachmentProgress()
                val summary = AttachmentUploadBatch.summary(outcomes)
                if (summary.quiet) {
                    ui.makeToastAlert(summary.message)
                } else {
                    ui.createErrorAlert(summary.title, summary.message, {})
                }
                refreshPendingEdits()
                onDone?.invoke()
            }, { e ->
                Log.e("zotero", "upload batch failed: $e")
                ui.stopUploadingAttachmentProgress()
                ui.createErrorAlert("Upload failed", "Details: ${e.message ?: e}", {})
                refreshPendingEdits()
                onDone?.invoke()
            })
    }

    /**
     * Attachments in My Library edited on this device and not uploaded yet; see
     * EditedAttachments. Hashes files whose size or mtime changed, so call it off the main
     * thread.
     */
    fun findEditedAttachments(): List<Item> {
        if (state.isUsingGroup() || !preferences.isAttachmentUploadingEnabled() || !isLoaded()) return emptyList()
        return editedAttachments.edited()
    }

    /** Where [item]'s file stands on this device; worked out off the main thread. */
    fun describeAttachment(item: Item, onState: (AttachmentLocalState) -> Unit) {
        Single.fromCallable {
            AttachmentLocalState.of(
                editedAttachments.currentMd5(item.itemKey), attachmentBase(item), zoteroDB.getMd5Key(item)
            )
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ onState(it) }, { e -> Log.e("zotero", "describing ${item.itemKey} failed: $e") })
    }

    /** md5 of the file this device last downloaded/uploaded for [item]; see AttachmentBase. */
    private fun attachmentBase(item: Item): String? = editedAttachments.base(item)

    private fun recordBase(item: Item, md5: String) = editedAttachments.recordBase(item, md5)

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
                itemSource.invalidate() // the sync may have changed anything
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
        itemSource.invalidate()
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

    // a GROUP BY over every tag: 40 ms on the emulator, too long for the main thread on a tablet
    override fun loadTags(onResult: (List<Pair<String, Int>>) -> Unit) {
        Single.fromCallable { itemSource.tagCounts() }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(onResult) { e ->
                Log.e(TAG, "counting tags failed: $e")
                onResult(emptyList())
            }
    }

    override fun deleteLocalAttachment(attachment: Item) {
        val delete = {
            attachmentStorageManager.deleteAttachment(attachment)
            refreshPendingEdits()
            ui.makeToastAlert("Deleted from this device: ${attachment.getTitle()}")
        }
        Single.fromCallable {
            editedAttachments.isEdited(attachment)
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ edited ->
                if (edited) {
                    ui.createYesNoPrompt(
                        "Delete your edits?",
                        "This copy has edits that haven't been uploaded. Deleting it loses them.",
                        "Delete", "Keep", delete, {}
                    )
                } else {
                    delete()
                }
            }, { e -> Log.e("zotero", "checking ${attachment.itemKey} for edits failed: $e") })
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