package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.mickstarify.zooforzotero.LibraryActivity.ViewModels.LibraryListViewModel
import com.mickstarify.zooforzotero.LibraryActivity.ViewModels.LibraryLoadingScreenViewModel
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentLocalState
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item

/**
 * The library screen's logic. It lives in [LibraryViewModel], so it outlives the
 * activity; [view] is whichever activity is on screen (see [attach]).
 */
class LibraryActivityPresenter(private val context: Context) : Contract.Presenter {
    lateinit var view: LibraryActivity
        private set

    // What the screen shows, kept here so a recreated activity just renders it again
    val title = MutableLiveData<String>()
    val subtitle = MutableLiveData<String?>()
    val sidebar = MutableLiveData<List<SidebarRow>>()
    val pendingEdits = MutableLiveData(0)

    override lateinit var libraryListViewModel: LibraryListViewModel
    override lateinit var libraryLoadingViewModel: LibraryLoadingScreenViewModel

    /** Opens My Library ([GroupInfo.NO_GROUP_ID]) or a group at its top, from the sidebar. */
    override fun openLibrary(groupId: Int) {
        if (groupId == GroupInfo.NO_GROUP_ID) {
            model.openMyLibrary()
        } else {
            model.getGroupById(groupId)?.also {
                model.startGroupSync(it)
            }
        }
    }

    override fun startUploadingAttachmentProgress(attachment: Item, position: Int, total: Int) {
        view.showAttachmentUploadProgress(attachment, position, total)
    }

    override fun stopUploadingAttachmentProgress() {
        view.hideAttachmentUploadProgress()
    }

    // the settings as the screen last applied them; Settings is another activity
    private var subcollectionItemsShown: Boolean? = null
    private var groupLibrariesShown: Boolean? = null

    override fun onResume() {
        if (model.isLoaded()) {
            model.checkAttachmentStorageAccess()
            model.refreshPendingEdits()
            val shown = subcollectionItemsShown
            if (shown != null && shown != model.preferences.showSubcollectionItems()) {
                redisplayItems()
            }
            val groupsOn = model.preferences.isGroupLibrarySyncEnabled()
            if (groupLibrariesShown != null && groupsOn != groupLibrariesShown) {
                model.setGroupLibrariesEnabled(groupsOn)
            }
            groupLibrariesShown = groupsOn
        }
        // A sync that ended while the activity was stopped (display asleep) couldn't leave
        // the loading screen: navigation is refused once the activity has saved its state.
        // One that was cut off partway (Doze took the network) continues from its page.
        if (model.isLoaded() && !model.isSyncing() && !model.resumeInterruptedSync() &&
            view.getCurrentScreen() == AvailableScreens.LIBRARY_LOADING_SCREEN
        ) {
            view.showScreen(R.id.libraryListFragment)
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
        val groupId = model.state.currentGroup.id
        val current = model.getCurrentCollection()
        if (current != "unset") model.preferences.setLibraryPlace(groupId, current)
        val tree = CollectionTree(model.getCollections())
        var expanded = model.preferences.getSidebarExpandedCollections(groupId)
        val hiddenBy = tree.ancestorsOf(current).filter { it !in expanded }
        if (hiddenBy.isNotEmpty()) {
            expanded = expanded + hiddenBy
            model.preferences.setSidebarExpandedCollections(groupId, expanded)
        }
        sidebar.value = SidebarRows.build(
            model.getGroups(), model.preferences.isSidebarGroupsExpanded(),
            groupId, current, tree, expanded
        )
    }

    fun toggleSidebarGroups() {
        model.preferences.setSidebarGroupsExpanded(!model.preferences.isSidebarGroupsExpanded())
        refreshSidebar()
    }

    fun toggleSidebarCollection(key: String) {
        val groupId = model.state.currentGroup.id
        val expanded = model.preferences.getSidebarExpandedCollections(groupId)
        model.preferences.setSidebarExpandedCollections(
            groupId, if (key in expanded) expanded - key else expanded + key
        )
        refreshSidebar()
    }

    override fun modifyNote(note: Note) {
        model.edits.modifyNote(note)
    }

    override fun deleteNote(note: Note) {
        model.edits.deleteNote(note)
    }

    override fun redisplayItems() {
        // Redraws the current place. Called after a sync finishes and after sort/filter changes.
        if (model.isLoaded() && model.getCurrentCollection() != "unset") {
            setCollection(model.getCurrentCollection())
        }
    }

    override fun cancelAttachmentDownload() {
        model.attachments.cancelAttachmentDownload()
        view.hideAttachmentDownloadProgress()
    }

    override fun isShowingContent(): Boolean {
        return model.isDisplayingItems
    }

    override fun updateLibraryRefreshProgress(
        progress: Int,
        total: Int,
        message: String
    ) {
        Log.d("zotero", "Updating library loading progress.")

        if (total > 25 && view.getCurrentScreen() != AvailableScreens.LIBRARY_LOADING_SCREEN) {
            view.showScreen(R.id.libraryLoadingScreen)
        }

        // total before progress: each observer rewrites the "Downloading X of Y" label,
        // so the last one set wins. An empty message must not blank that label.
        libraryLoadingViewModel.setTotalAmountOfEntries(total)
        libraryLoadingViewModel.setAmountOfDownloadedEntries(progress)
        if (message.isNotEmpty()) {
            libraryLoadingViewModel.setLoadingMessage(message)
        }

//        view.updateLibraryLoadingProgress(progress, total, message)
    }

    fun warmSearchIndex() = model.warmSearchIndex()

    /** The open library's search, or "" for none. */
    fun currentSearch(): String = model.librarySearch

    /** Narrows every list of the open library to items matching [query]; "" ends the search. */
    fun search(query: String) {
        model.librarySearch = query
        redisplayItems()
    }

    override fun loadTags(onResult: (List<Pair<String, Int>>) -> Unit) {
        if (model.isLoaded()) model.loadTags(onResult) else onResult(emptyList())
    }

    /** The open library: GroupInfo.NO_GROUP_ID for My Library, else the group's id. */
    fun currentLibraryId(): Int = model.state.currentGroup.id

    fun getLibraryFilter(): LibraryFilter = model.libraryFilter

    /** Sets the list's filter and redraws the list. */
    fun setLibraryFilter(filter: LibraryFilter) {
        model.libraryFilter = filter
        redisplayItems()
    }

    /**
     * Toolbar subtitle naming the active search and filter with the count, if the list goes
     * through them; and what the list says if it is empty.
     */
    private fun showFilterSummary() {
        val search = model.librarySearch
        val filter = model.libraryFilter
        val shown = model.lastFilteredCount
        subtitle.value =
            if ((search.isEmpty() && !filter.isActive) || shown == null) null
            else listOfNotNull(
                "Search: “$search”".takeIf { search.isNotEmpty() },
                "Filter: ${filter.describe()}".takeIf { filter.isActive },
                "$shown " + if (shown == 1) "item" else "items",
            ).joinToString(" · ")
        libraryListViewModel.setEmptyMessage(
            EmptyListMessage.forList(search, filter, shown != null, model.getCurrentCollection())
        )
    }

    override fun showLoadingAlertDialog(message: String) {
        view.showLoadingAlertDialog(message)
    }

    override fun hideLoadingAlertDialog() {
        view.hideLoadingAlertDialog()
    }

    override fun attachmentDownloadError(message: String) {
        view.hideAttachmentDownloadProgress()
        if (message == "") {
            createErrorAlert("Couldn't download the file", "Check your connection and try again.") { }
        } else {
            createErrorAlert("Couldn't download the file", message) { }
        }
    }

    override fun finishDownloadingAttachment() {
        view.hideAttachmentDownloadProgress()
    }

    override fun finishLibrary() {
        view.finish()
    }

    override fun createYesNoPrompt(
        title: String,
        message: String,
        yesText: String,
        noText: String,
        onYesClick: () -> Unit,
        onNoClick: () -> Unit
    ) {
        view.createYesNoPrompt(title, message, yesText, noText, onYesClick, onNoClick)
    }

    override fun showPendingEdits(count: Int) {
        pendingEdits.value = count
    }

    override fun showAttachmentStates(onDevice: Set<String>, edited: Set<String>) {
        view.showAttachmentStates(onDevice, edited)
    }

    override fun chooseAttachmentsToUpload(labels: List<String>, onUpload: (List<Int>) -> Unit) {
        view.chooseAttachmentsToUpload(labels, onUpload)
    }

    override fun showBasicSyncAnimation() {
        libraryListViewModel.setIsShowingLoadingAnimation(true)
    }

    override fun hideBasicSyncAnimation() {
        libraryListViewModel.setIsShowingLoadingAnimation(false)
        // we are finished loading and should hide the loading screen.
        view.showScreen(R.id.libraryListFragment)
    }

    override fun openAttachment(item: Item) {
        model.attachments.openAttachment(item)
    }

    override fun updateAttachmentDownloadProgress(progress: Long, total: Long) {
        val progressKB = (progress / 1000).toInt()
        val totalKB = (total / 1000).toInt()
        view.updateAttachmentDownloadProgress(progressKB, totalKB)
    }


    override fun showLibraryLoadingAnimation() {
//        view.showLoadingAnimation(showScreen = true)
        title.value = "Loading"

        if (view.getCurrentScreen() != AvailableScreens.LIBRARY_LOADING_SCREEN) {
            view.showScreen(R.id.libraryLoadingScreen)
        }
    }

    override fun hideLibraryLoadingAnimation() {
//        view.hideLoadingAnimation()
//        view.hideLibraryContentDisplay()
        Log.d("zotero", "loading library list fragment")
        view.showScreen(R.id.libraryListFragment)
    }

    override fun requestLibraryRefresh() {
        libraryListViewModel.setIsShowingLoadingAnimation(true)
        model.refreshLibrary(useSmallLoadingAnimation = true)
    }

    override fun selectItem(
        item: Item,
        longPress: Boolean
    ) {
        Log.d("zotero", "pressed ${item.itemType}")
        if (item.itemType == "attachment") {
            this.openAttachment(item)
        } else if (item.itemType == "note") {
            val note = Note(item)
            view.showNote(note)
        } else {
            model.completeItem(item)
            model.selectedItem = item
            view.showItemDialog()
            model.fetchChildrenWhileSyncing(item)
        }
    }

    fun editTag(item: Item, tag: String, add: Boolean) = model.edits.editTag(item, tag, add)

    fun addNote(item: Item, text: String) = model.edits.addNote(item, text)

    fun hasPendingEdits(item: Item) = model.hasPendingEdits(item)

    fun canEditItems() = !model.state.isUsingGroup()

    override fun showEditedItem(item: Item) {
        libraryListViewModel.onItemChanged(item)
        // only a list that can depend on tags is redrawn; a large one is slow to redraw
        if (model.libraryFilter.tags.isNotEmpty() ||
            model.librarySearch.isNotEmpty()
        ) redisplayItems()
    }

    override fun refreshItemView() {
        val item = model.selectedItem
        if (item != null) {
            view.closeItemView()
            view.showItemDialog()
        }
    }

    override fun openMyPublications() {
        if (!model.isLoaded()) {
            Log.e("zotero", "tried to change collection before fully loaded!")
            return
        }
        title.value = "My Publications"
        model.setCurrentCollection("zooforzotero_my_publications")
        showList(LibraryPlace.MyPublications)
        refreshSidebar()
    }

    override fun openTrash() {
        if (!model.isLoaded()) {
            Log.e("zotero", "tried to change collection before fully loaded!")
            return
        }
        title.value = "Trash"
        model.setCurrentCollection("zooforzotero_Trash")
        showList(LibraryPlace.Trash)
        refreshSidebar()
    }

    /**
     * Builds the list for [place] off the main thread and shows it with its subtitle. A
     * list asked for meanwhile replaces this one (see LibraryActivityModel.listItems).
     */
    private fun showList(place: LibraryPlace) {
        model.listItems(place) { pages ->
            model.isDisplayingItems = pages.size > 0
            libraryListViewModel.setItems(pages)
            showFilterSummary()
        }
    }

    override fun uploadAttachment(item: Item) {
        model.attachments.uploadAttachment(item)
    }

    override fun uploadEditedAttachments() {
        if (model.isLoaded()) {
            model.attachments.chooseEditedAttachmentsToUpload()
        }
    }

    override fun requestForceResync() {
        model.destroyLibrary()
    }

    override fun backButtonPressed() {
        model.loadPriorState()
        refreshSidebar()
    }

    override fun setCollection(collectionKey: String) {
        /*SetCollection is the method used to display items on the listView. It
        * has to get the data, then sort it, then provide it to the view.*/
        if (!model.isLoaded()) {
            Log.e("zotero", "tried to change collection before fully loaded!")
            return
        }
        Log.d("zotero", "Got request to change collection to ${collectionKey}")
        subcollectionItemsShown = model.preferences.showSubcollectionItems()
        model.setCurrentCollection(collectionKey)
        if (collectionKey == "zooforzotero_Trash") {
            openTrash()
            return
        }
        if (collectionKey == "zooforzotero_my_publications") {
            openMyPublications()
            return
        }
        val place = if (collectionKey == "all" && !model.isUsingGroups()) {
            title.value = "My Library"
            LibraryPlace.Library
        } else if (collectionKey == "unfiled_items") {
            title.value = "Unfiled Items"
            LibraryPlace.Unfiled
        } else if (collectionKey == "group_all" && model.isUsingGroups()) {
            title.value = model.getCurrentGroup()?.name ?: "ERROR"
            LibraryPlace.Library
        } else {
            // a collection, in My Library or the open group;
            // subcollections are reached through the sidebar's tree, not as rows here
            title.value = model.getCollectionFromKey(collectionKey)?.name ?: "Unknown Collection"
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
        view.createErrorAlert(title, message, onClick)
    }

    override fun makeToastAlert(message: String) {
        view.makeToastAlert(message)
    }

    // created at the first attach: its constructor can already show error dialogs
    private val model by lazy { LibraryActivityModel(this, context) }

    private var started = false

    /**
     * Takes over a newly created activity: the first one starts loading the library;
     * a recreated one (rotation) is drawn from what the model already holds, so the
     * library, the open group and Back all survive. See [LibraryViewModel].
     */
    fun attach(view: LibraryActivity) {
        this.view = view
        libraryListViewModel = ViewModelProvider(view).get(LibraryListViewModel::class.java)
        libraryLoadingViewModel =
            ViewModelProvider(view).get(LibraryLoadingScreenViewModel::class.java)

        view.initUI()

        if (!started) {
            started = true
            start()
        } else if (model.isLoaded()) {
            // the list, title, sidebar and edit count are LiveData the activity observes;
            // only the badges' snapshot is refreshed (files may have changed meanwhile)
            model.refreshPendingEdits()
        } else {
            view.showScreen(R.id.libraryLoadingScreen)
        }
    }

    private fun start() {
        groupLibrariesShown = model.preferences.isGroupLibrarySyncEnabled()
        title.value = "Loading"
        view.showScreen(R.id.libraryLoadingScreen)
        libraryLoadingViewModel.setLoadingMessage("Loading your library")

        Log.d(
            "zotero",
            "On Launch have model.shouldIUpdateLibrary()=${model.shouldIUpdateLibrary()}"
        )
        if (model.isFirstSync()) {
            Log.d("zotero", "First sync - loading library from scratch")
            model.loadGroups()
            // full loading screen with item counts, not the pull-to-refresh spinner:
            // a first sync of a large library takes many minutes.
            model.refreshLibrary(useSmallLoadingAnimation = false)
        } else if (model.shouldIUpdateLibrary()) {
            Log.d("zotero", "Updating library")
            model.loadGroups()
            model.loadLibraryLocally {
                this.requestLibraryRefresh()
            }
        } else {
            Log.d("zotero", "Loading library locally")
            model.loadGroups()
            model.loadLibraryLocally()
        }

        // observed for the presenter's lifetime, not the activity's: re-observing in a
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
        // the empty list's button: clears whatever it named, search and filter alike
        observe(libraryListViewModel.getOnClearFilterRequested()) {
            model.librarySearch = ""
            setLibraryFilter(LibraryFilter())
        }
    }

    private val observers = mutableListOf<() -> Unit>()

    private fun <T> observe(data: LiveData<T>, onChange: (T) -> Unit) {
        val observer = Observer<T> { onChange(it) }
        data.observeForever(observer)
        observers.add { data.removeObserver(observer) }
    }

    /** The activity is finishing for good; see [LibraryViewModel.onCleared]. */
    fun onCleared() {
        observers.forEach { it() }
        observers.clear()
    }

    override fun startActivity(intent: Intent) {
        view.startActivity(intent)
    }

    fun describeAttachment(attachment: Item, onState: (AttachmentLocalState) -> Unit) {
        model.attachments.describeAttachment(attachment, onState)
    }

    fun uploadChanges(attachment: Item) {
        model.attachments.uploadChanges(attachment)
    }

    fun downloadAttachmentOnly(attachment: Item) {
        model.attachments.downloadAttachmentOnly(attachment)
    }

    fun deleteLocalAttachment(attachment: Item) {
        model.attachments.deleteLocalAttachment(attachment)
    }
}
