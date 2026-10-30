package com.mickstarify.zooforzotero.LibraryActivity

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mickstarify.zooforzotero.SyncSetup.domain.AuthenticationStorageImpl
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.mickstarify.zooforzotero.LibraryActivity.ViewModels.LibraryListViewModel
import com.mickstarify.zooforzotero.LibraryActivity.ViewModels.LibraryLoadingScreenViewModel
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.SyncStatus
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentLocalState
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentCounts
import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentDownloadWorker
import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentDownloads
import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentKind
import androidx.work.WorkInfo

/**
 * The library screen's logic and what it shows. As a ViewModel it outlives the activity:
 * a rotation keeps the loaded library, the open group and Back, where every rotation
 * once built a new presenter and model, reloaded the library and lost the place. The
 * activity renders the LiveData here and runs [commands]; the work is done by the parts
 * in LibraryComponents, which report back through [LibraryUi].
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application), LibraryUi {
    private val context: Context get() = getApplication()

    /** Calls to the activity on screen, whichever it is; see ScreenCommands. */
    val commands = ScreenCommands<LibraryScreen>()

    // What the screen shows, kept here so a recreated activity just renders it again
    val title = MutableLiveData<String>()
    val subtitle = MutableLiveData<String?>()
    val sidebar = MutableLiveData<List<SidebarRow>>()
    val pendingEdits = MutableLiveData(0)
    /** The running sync, or the last one (running false); null before any. */
    val syncStatus = MutableLiveData<SyncStatus?>()
    val screen = MutableLiveData<AvailableScreens>()

    private fun showScreen(next: AvailableScreens) {
        if (screen.value != next) screen.value = next
    }

    private lateinit var libraryListViewModel: LibraryListViewModel
    private lateinit var libraryLoadingViewModel: LibraryLoadingScreenViewModel

    /** Opens My Library ([GroupInfo.NO_GROUP_ID]) or a group at its top, from the sidebar. */
    fun openLibrary(groupId: Int) {
        if (groupId == GroupInfo.NO_GROUP_ID) {
            openMyLibrary()
        } else {
            parts.groups.getGroupById(groupId)?.also {
                parts.sync.startGroupSync(it)
            }
        }
    }

    override fun startUploadingAttachmentProgress(attachment: Item, position: Int, total: Int) {
        commands.send { it.showAttachmentUploadProgress(attachment, position, total) }
    }

    override fun stopUploadingAttachmentProgress() {
        commands.send { it.hideAttachmentUploadProgress() }
    }

    // the settings as the screen last applied them; Settings is another activity
    private var subcollectionItemsShown: Boolean? = null
    private var groupLibrariesShown: Boolean? = null

    fun onResume() {
        if (library.isLoaded()) {
            checkAttachmentStorageAccess()
            parts.deviceState.refresh()
            val shown = subcollectionItemsShown
            if (shown != null && shown != parts.preferences.showSubcollectionItems()) {
                redisplayItems()
            }
            val groupsOn = parts.preferences.isGroupLibrarySyncEnabled()
            if (groupLibrariesShown != null && groupsOn != groupLibrariesShown) {
                setGroupLibrariesEnabled(groupsOn)
            }
            groupLibrariesShown = groupsOn
        }
        // A sync that ended while the activity was stopped (display asleep) couldn't leave
        // the loading screen: navigation is refused once the activity has saved its state.
        // One that was cut off partway (Doze took the network) continues from its page.
        if (library.isLoaded() && !parts.sync.isSyncing() && !parts.sync.resumeInterruptedSync() &&
            screen.value == AvailableScreens.LIBRARY_LOADING_SCREEN
        ) {
            showScreen(AvailableScreens.LIBRARY_LISTVIEW_SCREEN)
        }
    }

    override fun showGroups() {
        refreshSidebar()
    }

    /**
     * Redraws the sidebar for the open library and what the list shows. A collection
     * opened from elsewhere (Back, a collection row in search) has its parents expanded.
     */
    fun refreshSidebar() {
        val groupId = library.state.currentGroup.id
        val current = navigator.currentCollection()
        if (current != "unset") parts.preferences.setLibraryPlace(groupId, current)
        val tree = CollectionTree(library.collections)
        var expanded = parts.preferences.getSidebarExpandedCollections(groupId)
        val hiddenBy = tree.ancestorsOf(current).filter { it !in expanded }
        if (hiddenBy.isNotEmpty()) {
            expanded = expanded + hiddenBy
            parts.preferences.setSidebarExpandedCollections(groupId, expanded)
        }
        sidebar.value = SidebarRows.build(
            parts.groups.getGroups(), parts.preferences.isSidebarGroupsExpanded(),
            groupId, current, tree, expanded
        )
    }

    fun toggleSidebarGroups() {
        parts.preferences.setSidebarGroupsExpanded(!parts.preferences.isSidebarGroupsExpanded())
        refreshSidebar()
    }

    fun toggleSidebarCollection(key: String) {
        val groupId = library.state.currentGroup.id
        val expanded = parts.preferences.getSidebarExpandedCollections(groupId)
        parts.preferences.setSidebarExpandedCollections(
            groupId, if (key in expanded) expanded - key else expanded + key
        )
        refreshSidebar()
    }

    fun modifyNote(note: Note) {
        parts.edits.modifyNote(note)
    }

    fun deleteNote(note: Note) {
        parts.edits.deleteNote(note)
    }

    override fun redisplayItems() {
        // Redraws the current place. Called after a sync finishes and after sort/filter changes.
        if (library.isLoaded() && navigator.currentCollection() != "unset") {
            setCollection(navigator.currentCollection())
        }
    }

    fun cancelAttachmentDownload() {
        parts.attachments.cancelAttachmentDownload()
        commands.send { it.hideAttachmentDownloadProgress() }
    }

    override fun updateLibraryRefreshProgress(
        progress: Int,
        total: Int,
        message: String
    ) {
        Log.d("zotero", "Updating library loading progress.")

        if (total > 25 && screen.value != AvailableScreens.LIBRARY_LOADING_SCREEN) {
            showScreen(AvailableScreens.LIBRARY_LOADING_SCREEN)
        }

        // total before progress: each observer rewrites the "Downloading X of Y" label,
        // so the last one set wins. An empty message must not blank that label.
        libraryLoadingViewModel.setTotalAmountOfEntries(total)
        libraryLoadingViewModel.setAmountOfDownloadedEntries(progress)
        if (message.isNotEmpty()) {
            libraryLoadingViewModel.setLoadingMessage(message)
        }

    }

    fun warmSearchIndex() = parts.lists.warmSearchIndex()

    /** The open library's search, or "" for none. */
    fun currentSearch(): String = parts.lists.librarySearch

    /** Narrows every list of the open library to items matching [query]; "" ends the search. */
    fun search(query: String) {
        parts.lists.librarySearch = query
        redisplayItems()
    }

    fun loadTags(onResult: (List<Pair<String, Int>>) -> Unit) {
        if (library.isLoaded()) parts.lists.loadTags(onResult) else onResult(emptyList())
    }

    /** The open library: GroupInfo.NO_GROUP_ID for My Library, else the group's id. */
    fun currentLibraryId(): Int = library.state.currentGroup.id

    fun getLibraryFilter(): LibraryFilter = parts.lists.libraryFilter

    /** Sets the list's filter and redraws the list. */
    fun setLibraryFilter(filter: LibraryFilter) {
        parts.lists.libraryFilter = filter
        redisplayItems()
    }

    private var filterSummary: String? = null
    private var downloadStatus: String? = null

    /** Bulk downloads at work, for the menu's "Stop downloads". */
    val downloadsActive = MutableLiveData(false)

    private fun showSubtitle() {
        subtitle.value = listOfNotNull(downloadStatus, filterSummary).joinToString(" · ").ifEmpty { null }
    }

    /**
     * Follows the bulk downloads' workers: "Downloading 3 of 12" in the subtitle while they
     * work, the list's PDF badges as files arrive, and what was done when they stop.
     */
    private fun followDownloads() {
        var lastDone = -1
        observe(AttachmentDownloads.workInfos(context)) { infos ->
            val active = infos.any { !it.state.isFinished }
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            val done = running?.progress?.getInt(AttachmentDownloadWorker.DONE, -1) ?: -1
            val total = running?.progress?.getInt(AttachmentDownloadWorker.TOTAL, 0) ?: 0
            downloadStatus = when {
                !active -> null
                total > 0 && done >= 0 -> "Downloading ${minOf(done + 1, total)} of $total"
                running == null -> "Downloads waiting for a network"
                else -> "Downloading…"
            }
            showSubtitle()
            if (done != lastDone && library.isLoaded()) parts.deviceState.refresh()
            lastDone = done
            // also a round that ended while the app was away (or killed) is told at start
            if (!active) reportDownloads()
            if (downloadsActive.value != active) downloadsActive.value = active
        }
    }

    private fun reportDownloads() {
        val state = AttachmentDownloads.queue(context).takeReport() ?: return
        fun files(n: Int) = if (n == 1) "1 file" else "$n files"
        if (state.stopped) {
            makeToastAlert("Downloads stopped. ${files(state.downloaded)} downloaded.")
        } else if (state.failed == 0) {
            makeToastAlert(
                if (state.downloaded == 0) "No files were downloaded." else "Downloaded ${files(state.downloaded)}."
            )
        } else {
            createErrorAlert(
                "Some files weren't downloaded",
                (if (state.downloaded == 0) "${files(state.failed)} couldn't be downloaded."
                else "Downloaded ${files(state.downloaded)}; ${files(state.failed)} couldn't be.") +
                        state.firstError?.let { "\n\n$it" }.orEmpty()
            ) {}
        }
    }

    fun stopDownloads() = AttachmentDownloads.stop(context)

    fun countSelectedAttachments(onResult: (Map<AttachmentKind, AttachmentCounts>) -> Unit) =
        parts.attachments.countAttachments(selectedKeys(), onResult)

    fun downloadAttachments(attachments: List<Item>) = parts.attachments.downloadAttachments(attachments)

    fun findEditedFiles(files: List<Item>, onResult: (List<Item>) -> Unit) = parts.attachments.findEdited(files, onResult)

    fun deleteLocalFiles(files: List<Item>) = parts.attachments.deleteLocalAttachments(files)

    /**
     * Toolbar subtitle naming the active search and filter with the count, if the list goes
     * through them; and what the list says if it is empty.
     */
    private fun showFilterSummary() {
        val search = parts.lists.librarySearch
        val filter = parts.lists.libraryFilter
        val shown = parts.lists.lastFilteredCount
        filterSummary =
            if ((search.isEmpty() && !filter.isActive) || shown == null) null
            else listOfNotNull(
                "Search: “$search”".takeIf { search.isNotEmpty() },
                "Filter: ${filter.describe()}".takeIf { filter.isActive },
                "$shown " + if (shown == 1) "item" else "items",
            ).joinToString(" · ")
        showSubtitle()
        libraryListViewModel.setEmptyMessage(
            EmptyListMessage.forList(search, filter, shown != null, navigator.currentCollection())
        )
    }

    override fun showLoadingAlertDialog(message: String) {
        commands.send { it.showLoadingAlertDialog(message) }
    }

    override fun hideLoadingAlertDialog() {
        commands.send { it.hideLoadingAlertDialog() }
    }

    override fun attachmentDownloadError(message: String) {
        commands.send { it.hideAttachmentDownloadProgress() }
        if (message == "") {
            createErrorAlert("Couldn't download the file", "Check your connection and try again.") { }
        } else {
            createErrorAlert("Couldn't download the file", message) { }
        }
    }

    override fun finishDownloadingAttachment() {
        commands.send { it.hideAttachmentDownloadProgress() }
    }

    override fun finishLibrary() {
        commands.send { it.finish() }
    }

    override fun createYesNoPrompt(
        title: String,
        message: String,
        yesText: String,
        noText: String,
        onYesClick: () -> Unit,
        onNoClick: () -> Unit
    ) {
        commands.send { it.createYesNoPrompt(title, message, yesText, noText, onYesClick, onNoClick) }
    }

    override fun showSyncStatus(status: SyncStatus) {
        syncStatus.value = status
    }

    override fun showPendingEdits(count: Int) {
        pendingEdits.value = count
    }

    override fun showAttachmentStates(onDevice: Set<String>, edited: Set<String>) {
        commands.send { it.showAttachmentStates(onDevice, edited) }
    }

    override fun chooseAttachmentsToUpload(labels: List<String>, onUpload: (List<Int>) -> Unit) {
        commands.send { it.chooseAttachmentsToUpload(labels, onUpload) }
    }

    override fun showBasicSyncAnimation() {
        libraryListViewModel.setIsShowingLoadingAnimation(true)
    }

    override fun hideBasicSyncAnimation() {
        libraryListViewModel.setIsShowingLoadingAnimation(false)
        // we are finished loading and should hide the loading screen.
        showScreen(AvailableScreens.LIBRARY_LISTVIEW_SCREEN)
    }

    fun openAttachment(item: Item) {
        parts.attachments.openAttachment(item)
    }

    override fun updateAttachmentDownloadProgress(progress: Long, total: Long) {
        val progressKB = (progress / 1000).toInt()
        val totalKB = (total / 1000).toInt()
        commands.send { it.updateAttachmentDownloadProgress(progressKB, totalKB) }
    }


    override fun showLibraryLoadingAnimation() {
        title.value = "Loading"

        if (screen.value != AvailableScreens.LIBRARY_LOADING_SCREEN) {
            showScreen(AvailableScreens.LIBRARY_LOADING_SCREEN)
        }
    }

    override fun hideLibraryLoadingAnimation() {
        Log.d("zotero", "loading library list fragment")
        showScreen(AvailableScreens.LIBRARY_LISTVIEW_SCREEN)
    }

    fun requestLibraryRefresh() {
        libraryListViewModel.setIsShowingLoadingAnimation(true)
        parts.sync.refreshLibrary(useSmallLoadingAnimation = true)
    }

    fun selectItem(
        item: Item,
        longPress: Boolean = false
    ) {
        Log.d("zotero", "pressed ${item.itemType}")
        if (item.itemType == "attachment") {
            this.openAttachment(item)
        } else if (item.itemType == "note") {
            val note = Note(item)
            commands.send { it.showNote(note) }
        } else {
            library.db.completeItem(item)
            selectedItem = item
            commands.send { it.showItemDialog() }
            parts.sync.fetchChildrenWhileSyncing(item)
        }
    }

    fun editTag(item: Item, tag: String, add: Boolean) = parts.edits.editTag(item, tag, add)

    fun addNote(item: Item, text: String) = parts.edits.addNote(item, text)

    fun hasPendingEdits(item: Item) = parts.deviceState.hasPendingEdits(item)

    fun canEditItems() = !library.state.isUsingGroup()

    override fun showEditedItem(item: Item) {
        libraryListViewModel.onItemChanged(item)
        showEditedItems()
    }

    override fun showEditedItems(collectionsChanged: Boolean) {
        // only a list that can depend on tags is redrawn; a large one is slow to redraw
        if (collectionsChanged || parts.lists.libraryFilter.tags.isNotEmpty() ||
            parts.lists.librarySearch.isNotEmpty()
        ) redisplayItems()
    }

    /** The items selected in the list, in the order chosen. */
    private fun selectedKeys() = libraryListViewModel.getSelection().value!!.keys()

    fun editSelectedTags(tag: String, add: Boolean) = parts.edits.editTags(selectedKeys(), tag, add)

    /** The open collection's key and name, or null if the list isn't a collection. */
    fun currentCollection(): Pair<String, String>? {
        if (!library.isLoaded()) return null
        val key = navigator.currentCollection()
        return library.collection(key)?.let { key to it.name }
    }

    /** The open library's collections, parents before children, with their depth. */
    fun collectionsForPicker(): List<Pair<Collection, Int>> = CollectionTree(library.collections).flattened()

    fun editSelectedCollection(collectionKey: String, name: String, add: Boolean) =
        parts.edits.editCollection(selectedKeys(), collectionKey, name, add)

    fun loadSelectedTags(onResult: (List<Pair<String, Int>>) -> Unit) =
        parts.edits.tagsOf(selectedKeys(), onResult)

    override fun refreshItemView() {
        val item = selectedItem
        if (item != null) {
            commands.send { it.closeItemView() }
            commands.send { it.showItemDialog() }
        }
    }

    fun openMyPublications() {
        if (!library.isLoaded()) {
            Log.e("zotero", "tried to change collection before fully loaded!")
            return
        }
        title.value = "My Publications"
        navigator.setCollection("zooforzotero_my_publications")
        showList(LibraryPlace.MyPublications)
        refreshSidebar()
    }

    fun openTrash() {
        if (!library.isLoaded()) {
            Log.e("zotero", "tried to change collection before fully loaded!")
            return
        }
        title.value = "Trash"
        navigator.setCollection("zooforzotero_Trash")
        showList(LibraryPlace.Trash)
        refreshSidebar()
    }

    /**
     * Builds the list for [place] off the main thread and shows it with its subtitle. A
     * list asked for meanwhile replaces this one (see LibraryLists.listItems).
     */
    private fun showList(place: LibraryPlace) {
        parts.lists.listItems(place) { pages ->
            // bulk actions on trashed items aren't offered
            libraryListViewModel.selectable = place != LibraryPlace.Trash
            libraryListViewModel.setItems(pages)
            showFilterSummary()
        }
    }

    fun uploadAttachment(item: Item) {
        parts.attachments.uploadAttachment(item)
    }

    fun uploadEditedAttachments() {
        if (library.isLoaded()) {
            parts.attachments.chooseEditedAttachmentsToUpload()
        }
    }

    fun requestForceResync() {
        parts.sync.destroyLibrary()
    }

    fun backButtonPressed() {
        loadPriorState()
        refreshSidebar()
    }

    override fun setCollection(collectionKey: String) {
        /*SetCollection is the method used to display items on the listView. It
        * has to get the data, then sort it, then provide it to the view.*/
        if (!library.isLoaded()) {
            Log.e("zotero", "tried to change collection before fully loaded!")
            return
        }
        Log.d("zotero", "Got request to change collection to ${collectionKey}")
        subcollectionItemsShown = parts.preferences.showSubcollectionItems()
        navigator.setCollection(collectionKey)
        if (collectionKey == "zooforzotero_Trash") {
            openTrash()
            return
        }
        if (collectionKey == "zooforzotero_my_publications") {
            openMyPublications()
            return
        }
        val place = if (collectionKey == "all" && !navigator.isUsingGroup()) {
            title.value = "My Library"
            LibraryPlace.Library
        } else if (collectionKey == "unfiled_items") {
            title.value = "Unfiled Items"
            LibraryPlace.Unfiled
        } else if (collectionKey == "group_all" && navigator.isUsingGroup()) {
            title.value = navigator.currentGroup()?.name ?: "ERROR"
            LibraryPlace.Library
        } else {
            // a collection, in My Library or the open group;
            // subcollections are reached through the sidebar's tree, not as rows here
            title.value = library.collection(collectionKey)?.name ?: "Unknown Collection"
            LibraryPlace.Collection(collectionKey)
        }
        showList(place)
        refreshSidebar()
    }

    override fun receiveCollections(collections: List<Collection>) {
        refreshSidebar()
    }

    override fun createErrorAlert(
        title: String,
        message: String,
        onClick: () -> Unit
    ) {
        commands.send { it.createErrorAlert(title, message, onClick) }
    }

    override fun makeToastAlert(message: String) {
        commands.send { it.makeToastAlert(message) }
    }

    // stores the current item being viewed by the user. (useful for refreshing the view)
    var selectedItem: Item? = null

    private val parts by lazy { LibraryComponents(this, context) { selectedItem } }
    private val library get() = parts.library
    private val navigator get() = library.navigator

    private var started = false

    /**
     * Takes over a newly created activity, given its [list] and [loading] ViewModels: the
     * first one starts loading the library; a recreated one (rotation) is drawn from what
     * is already loaded, so the library, the open group and Back all survive.
     */
    fun attach(list: LibraryListViewModel, loading: LibraryLoadingScreenViewModel) {
        // the activity's own ViewModels: the same instances across a rotation
        libraryListViewModel = list
        libraryLoadingViewModel = loading

        if (!started) {
            started = true
            start()
        } else if (library.isLoaded()) {
            // the list, title, sidebar and edit count are LiveData the activity observes;
            // only the badges' snapshot is refreshed (files may have changed meanwhile)
            parts.deviceState.refresh()
        } else {
            showScreen(AvailableScreens.LIBRARY_LOADING_SCREEN)
        }
    }

    private fun start() {
        checkCredentials()
        checkAttachmentStorageAccess()
        groupLibrariesShown = parts.preferences.isGroupLibrarySyncEnabled()
        title.value = "Loading"
        showScreen(AvailableScreens.LIBRARY_LOADING_SCREEN)
        libraryLoadingViewModel.setLoadingMessage("Loading your library")

        Log.d(
            "zotero",
            "On Launch have parts.sync.shouldIUpdateLibrary()=${parts.sync.shouldIUpdateLibrary()}"
        )
        if (parts.sync.isFirstSync()) {
            Log.d("zotero", "First sync - loading library from scratch")
            parts.groups.loadGroups()
            // full loading screen with item counts, not the pull-to-refresh spinner:
            // a first sync of a large library takes many minutes.
            parts.sync.refreshLibrary(useSmallLoadingAnimation = false)
        } else if (parts.sync.shouldIUpdateLibrary()) {
            Log.d("zotero", "Updating library")
            parts.groups.loadGroups()
            parts.sync.loadLibraryLocally {
                this.requestLibraryRefresh()
            }
        } else {
            Log.d("zotero", "Loading library locally")
            parts.groups.loadGroups()
            parts.sync.loadLibraryLocally()
        }

        // observed for this ViewModel's lifetime, not the activity's: re-observing in a
        // recreated activity would replay the last click (and reopen an attachment)
        observe(libraryListViewModel.getOnItemClicked()) { item ->
            this.selectItem(item, longPress = false)
        }
        observe(libraryListViewModel.getOnAttachmentClicked()) {
            this.openAttachment(it)
        }
        observe(libraryListViewModel.getOnLibraryRefreshRequested()) {
            this.requestLibraryRefresh()
        }
        followDownloads()
        // the empty list's button: clears whatever it named, search and filter alike
        observe(libraryListViewModel.getOnClearFilterRequested()) {
            parts.lists.librarySearch = ""
            setLibraryFilter(LibraryFilter())
        }
    }

    private val observers = mutableListOf<() -> Unit>()

    private fun <T> observe(data: LiveData<T>, onChange: (T) -> Unit) {
        val observer = Observer<T> { onChange(it) }
        data.observeForever(observer)
        observers.add { data.removeObserver(observer) }
    }

    /** The activity is finishing for good. */
    override fun onCleared() {
        observers.forEach { it() }
        observers.clear()
    }

    override fun startActivity(intent: Intent) {
        commands.send { it.startActivity(intent) }
    }

    fun describeAttachment(attachment: Item, onState: (AttachmentLocalState) -> Unit) {
        parts.attachments.describeAttachment(attachment, onState)
    }

    fun uploadChanges(attachment: Item) {
        parts.attachments.uploadChanges(attachment)
    }

    fun downloadAttachmentOnly(attachment: Item) {
        parts.attachments.downloadAttachmentOnly(attachment)
    }

    fun deleteLocalAttachment(attachment: Item) {
        parts.attachments.deleteLocalAttachment(attachment)
    }

    /**
     * Without a stored login the library can't sync: says so and closes the screen
     * (LibraryActivity sends the user to sign in when it can see that first).
     */
    private fun checkCredentials() {
        val auth = AuthenticationStorageImpl(context)
        if (!auth.hasCredentials()) {
            createErrorAlert(
                "Sign in again",
                "The stored Zotero login is no longer valid."
            ) { finishLibrary() }
            auth.destroyCredentials()
            library.db.clearItemsVersion()
        }
    }

    fun checkAttachmentStorageAccess() {
        try {
            if (parts.attachmentStorageManager.testStorage() == false) {
                throw Exception()
            }
        } catch (e: Exception) {
            Log.e("zotero", "error testing storage. ${e}")
            createErrorAlert(
                "Can't use the attachment folder",
                "The app can't access the folder set in Settings → Attachment folder, so " +
                        "it's back to the default. Choose the folder again in Settings.",
                {})
            parts.preferences.useExternalCache()
        }
    }

    /** Switches the list and the sidebar to My Library, at its top. */
    private fun openMyLibrary() {
        if (library.state.isUsingGroup()) {
            library.usePersonalLibrary()
            navigator.openLibrary(null)
            receiveCollections(library.collections)
        } else {
            navigator.openLibrary(null)
        }
        redisplayItems()
    }

    /**
     * Applies Settings → Group libraries at once. On: the groups are listed. Off: they
     * go from the sidebar, an open group closes (back to My Library) and Back forgets
     * places in groups. Groups only sync when opened, so nothing else depends on it.
     */
    private fun setGroupLibrariesEnabled(enabled: Boolean) {
        if (enabled) {
            parts.groups.loadGroups()
            return
        }
        parts.groups.clear()
        parts.sync.forgetOpeningGroup()
        if (navigator.leaveGroups()) {
            library.usePersonalLibrary()
            receiveCollections(library.collections)
            redisplayItems()
        }
        showGroups()
    }

    private var doubleBackToExitPressedOnce = false

    /** Back: the previous place in the library; at the top, twice within 2 s exits. */
    private fun loadPriorState() {
        val states = navigator.states
        // check to see if we're at the root level, the first state is a junk state, so we
        // check at 2.
        if (states.size <= 2) {
            if (doubleBackToExitPressedOnce) {
                finishLibrary()
                return
            }

            this.doubleBackToExitPressedOnce = true
            makeToastAlert("Press Back again to exit.")
            Handler(Looper.getMainLooper()).postDelayed({ doubleBackToExitPressedOnce = false }, 2000)
            return
        }
        val oldState = states.peek()
        states.pop()
        val state = library.state
        if (oldState.currentGroup.id != state.currentGroup.id) {
            // back into another library; its database was loaded when we were there
            if (state.isUsingGroup()) {
                library.useGroup(state.currentGroup.id)
            } else {
                library.usePersonalLibrary()
            }
            receiveCollections(library.collections)
            redisplayItems()
        } else if (oldState.currentCollection != state.currentCollection) {
            redisplayItems()
        } else {
            Log.e("zotero", "error unable to determine state differences!!")
        }
    }
}
