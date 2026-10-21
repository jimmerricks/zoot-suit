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
import com.mickstarify.zooforzotero.SortMethod
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.GroupInfo
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentLocalState
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import java.util.LinkedList
import java.util.Locale

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

    /** The key an item sorts by under [method]; ties are broken by title. */
    private fun sortKey(item: Item, method: SortMethod): String = when (method) {
        SortMethod.TITLE -> item.getTitle().lowercase(Locale.ROOT)
        SortMethod.DATE -> item.getSortableDateString()
        SortMethod.DATE_ADDED -> item.getSortableDateAddedString()
        SortMethod.AUTHOR -> {
            val authorText = item.getAuthor().lowercase(Locale.ROOT)
            // force empty authors to the bottom. Just like the zotero desktop client.
            if (authorText == "") "zzz" else authorText
        }
    }

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
        model.modifyNote(note)
    }

    override fun createNote(note: Note) {
        if (note.note.trim() != "") {
            model.createNote(note)
        }
    }

    override fun deleteNote(note: Note) {
        model.deleteNote(note)
    }

    override fun redisplayItems() {
        // Redraws whatever the current state shows. Called after a sync finishes and
        // after sort/filter changes, so it must keep a tag view a tag view.
        if (model.isLoaded()) {
            if (model.state.filterText != "") {
                showFilter(model.state.filterText)
            } else if (model.getCurrentCollection() != "unset") {
                setCollection(model.getCurrentCollection())
            }
        }
    }

    override fun cancelAttachmentDownload() {
        model.cancelAttachmentDownload()
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
            view.navController.navigate(R.id.libraryLoadingScreen)
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

    override fun isLiveSearchEnabled(): Boolean {
        return model.preferences.shouldLiveSearch()
    }

    override fun closeQuery() {
        this.setCollection(model.getCurrentCollection())
    }

    override fun addFilterState(query: String) {
        /* This method tells the model to add a filtered state to the the states stack
        * This will allow a user to use the back button to return to the library before
        * the filter was initiated. The need for this method is a little contrived but I had to hack
        * the functionality in so forgive me. */

        val oldState = model.state
        model.states.add(LibraryModelState().apply {
            this.currentGroup = oldState.currentGroup
            this.currentCollection = oldState.currentCollection
            this.filterText = query
        })
    }

    override fun getAllTags(): List<Pair<String, Int>> {
        return if (model.isLoaded()) model.getAllTags() else emptyList()
    }

    /** The open library: GroupInfo.NO_GROUP_ID for My Library, else the group's id. */
    fun currentLibraryId(): Int = model.state.currentGroup.id

    fun getLibraryFilter(): LibraryFilter = model.libraryFilter

    /** Sets the list's filter, redraws the list and reports what it now shows. */
    fun previewLibraryFilter(filter: LibraryFilter): LibraryFilterDialog.Preview {
        model.libraryFilter = filter
        redisplayItems()
        val shown = model.lastFilteredItems ?: emptyList()
        return LibraryFilterDialog.Preview(shown.size, LibraryItemFilter.tagCounts(shown))
    }

    /**
     * Toolbar subtitle naming the active filter and its count, if the list uses the filter;
     * and what the list says if it is empty. [query] is the search or tag view shown, if any.
     */
    private fun showFilterSummary(query: String = model.state.filterText) {
        val filter = model.libraryFilter
        val shown = model.lastFilteredItems
        subtitle.value =
            if (!filter.isActive || shown == null) null
            else "Filter: ${filter.describe()} · ${shown.size} " + if (shown.size == 1) "item" else "items"
        libraryListViewModel.setEmptyMessage(
            EmptyListMessage.forList(filter, shown != null, query, model.getCurrentCollection())
        )
    }

    override fun onTagOpen(tagName: String) {
        this.addFilterState("tag:${tagName}")
        showFilter("tag:${tagName}")
    }

    /** Shows the results for a filter state, e.g. "tag:to-read", with its title. */
    private fun showFilter(filterText: String) {
        this.filterEntries(filterText)
        if (filterText.startsWith("tag:")) {
            title.value = "Tag: ${filterText.removePrefix("tag:")}"
        }
    }

    override fun showLoadingAlertDialog(message: String) {
        view.showLoadingAlertDialog(message)
    }

    override fun hideLoadingAlertDialog() {
        view.hideLoadingAlertDialog()
    }

    override fun filterEntries(query: String) {
        Log.d("zotero", "filtering $query")
        if (query == "" || !model.isLoaded()) {
            //not going to waste my time lol.
            return
        }

        model.startListUpdate()
        if (query.startsWith("tag:")) {
            val tagName = query.substring(4) // remove tag:
            val entries = model.getItemsForTag(tagName).sort().map { ListEntry(it) }
            libraryListViewModel.setItems(entries)
            showFilterSummary(query)
            return
        }

        val collections = model.filterCollections(query)
        val items = model.filterItems(query).sort()

        val entries = LinkedList<ListEntry>()
        entries.addAll(collections.sortedBy { it.name.lowercase(Locale.ROOT) }
            .map { ListEntry(it) })
        entries.addAll(
            items
                .map { ListEntry(it) })

        Log.d("zotero", "setting items ${entries.size}")
        libraryListViewModel.setItems(entries)
        showFilterSummary(query)
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
        view.navController.navigate(R.id.libraryListFragment)
    }

    override fun openAttachment(item: Item) {
        model.openAttachment(item)
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
            (view as LibraryActivity).navController.navigate(R.id.libraryLoadingScreen)
        }
    }

    override fun hideLibraryLoadingAnimation() {
//        view.hideLoadingAnimation()
//        view.hideLibraryContentDisplay()
        Log.d("zotero", "loading library list fragment")
        (view as LibraryActivity).navController.navigate(R.id.libraryListFragment)
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
            model.selectedItem = item
            view.showItemDialog()
        }
    }

    override fun refreshItemView() {
        val item = model.selectedItem
        if (item != null) {
            view.closeItemView()
            view.showItemDialog()
        }
    }

    override fun openMyPublications() {
        model.startListUpdate()
        if (!model.isLoaded()) {
            Log.e("zotero", "tried to change collection before fully loaded!")
            return
        }
        title.value = "My Publications"
        val entries = model.getMyPublications().sort().map{ListEntry(it)}
        model.isDisplayingItems = entries.isNotEmpty()
        model.setCurrentCollection("zooforzotero_my_publications")
        libraryListViewModel.setItems(entries)
        showFilterSummary()
        refreshSidebar()
    }

    override fun openTrash() {
        model.startListUpdate()
        if (!model.isLoaded()) {
            Log.e("zotero", "tried to change collection before fully loaded!")
            return
        }
        title.value = "Trash"
        val entries = model.getTrashedItems().sort().map{ListEntry(it)}
        model.isDisplayingItems = entries.isNotEmpty()
        model.setCurrentCollection("zooforzotero_Trash")
        libraryListViewModel.setItems(entries)
        showFilterSummary()
        refreshSidebar()
    }

    override fun uploadAttachment(item: Item) {
        model.uploadAttachment(item)
    }

    override fun uploadEditedAttachments() {
        if (model.isLoaded()) {
            model.chooseEditedAttachmentsToUpload()
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
        model.startListUpdate()
        model.setCurrentCollection(collectionKey)
        if (collectionKey == "all" && !model.isUsingGroups()) {
            title.value = "My Library"
            val entries = model.getLibraryItems().sort().map { ListEntry(it) }
            model.isDisplayingItems = entries.size > 0
            libraryListViewModel.setItems(entries)
        } else if (collectionKey == "unfiled_items") {
            title.value = "Unfiled Items"
            val entries = model.getUnfiledItems().sort().map { ListEntry(it) }
            model.isDisplayingItems = entries.size > 0
            libraryListViewModel.setItems(entries)
        } else if (collectionKey == "zooforzotero_Trash"){
            this.openTrash()
        } else if (collectionKey == "zooforzotero_my_publications"){
            this.openMyPublications()
        }else if (collectionKey == "group_all" && model.isUsingGroups()) {
            title.value = model.getCurrentGroup()?.name ?: "ERROR"
            val entries = model.getLibraryItems().sort().map { ListEntry(it) }
            model.isDisplayingItems = entries.size > 0
            libraryListViewModel.setItems(entries)
        }
        // a collection, in My Library or the open group
        else {
            val collection = model.getCollectionFromKey(collectionKey)

            title.value = collection?.name ?: "Unknown Collection"
            // subcollections are reached through the sidebar's tree, not as rows here
            val entries = model.getItemsFromCollection(collectionKey).sort().map { ListEntry(it) }
            model.isDisplayingItems = entries.size > 0
            libraryListViewModel.setItems(entries)
        }
        showFilterSummary()
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
            view.navController.navigate(R.id.libraryLoadingScreen)
        }
    }

    private fun start() {
        groupLibrariesShown = model.preferences.isGroupLibrarySyncEnabled()
        title.value = "Loading"
        view.navController.navigate(R.id.libraryLoadingScreen)
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
        observe(libraryListViewModel.getOnCollectionClicked()) {
            this.setCollection(it.key)
        }
        observe(libraryListViewModel.getOnLibraryRefreshRequested()) {
            this.requestLibraryRefresh()
        }
        observe(libraryListViewModel.getOnClearFilterRequested()) {
            previewLibraryFilter(LibraryFilter())
        }
        observe(libraryListViewModel.getLibraryFilterText()) {
            Log.d("zotero", "got filter text $it")
            filterEntries(it)
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

    // extension function to sort lists of items
    // Keys are computed once per item, not per comparison: a note's title parses its HTML.
    private fun List<Item>.sort(): List<Item> {
        val groupId = model.state.currentGroup.id
        val method = model.preferences.getSortMethod(groupId)
        val sorted = this
            .map { Triple(sortKey(it, method), it.getTitle().lowercase(Locale.ROOT), it) }
            .sortedWith(compareBy<Triple<String, String, Item>>({ it.first }, { it.second }))
            .map { it.third }
        return if (model.preferences.isSortedAscendingly(groupId)) sorted else sorted.reversed()
    }

    fun describeAttachment(attachment: Item, onState: (AttachmentLocalState) -> Unit) {
        model.describeAttachment(attachment, onState)
    }

    fun uploadChanges(attachment: Item) {
        model.uploadChanges(attachment)
    }

    fun downloadAttachmentOnly(attachment: Item) {
        model.downloadAttachmentOnly(attachment)
    }

    fun deleteLocalAttachment(attachment: Item) {
        model.deleteLocalAttachment(attachment)
    }
}
