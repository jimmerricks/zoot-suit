package com.mickstarify.zooforzotero.LibraryActivity

import android.app.ProgressDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import com.mickstarify.zooforzotero.SyncSetup.domain.AuthenticationStorageImpl
import com.mickstarify.zooforzotero.SyncSetup.SyncSetupActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.getSystemService
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mickstarify.zooforzotero.LibraryActivity.ItemView.ItemAttachmentEntry
import com.mickstarify.zooforzotero.LibraryActivity.ItemView.ItemViewFragment
import com.mickstarify.zooforzotero.LibraryActivity.Notes.NoteInteractionListener
import com.mickstarify.zooforzotero.LibraryActivity.Notes.NoteView
import com.mickstarify.zooforzotero.LibraryActivity.ViewModels.AttachmentStates
import com.mickstarify.zooforzotero.LibraryActivity.ViewModels.LibraryListViewModel
import com.mickstarify.zooforzotero.LibraryActivity.ViewModels.LibraryLoadingScreenViewModel
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.SyncStatus
import com.mickstarify.zooforzotero.SettingsActivity
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note
import com.mickstarify.zooforzotero.ZoteroStorage.AttachmentLocalState
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import dagger.hilt.android.AndroidEntryPoint

private const val TAG = "LibraryActivity"

@AndroidEntryPoint
class LibraryActivity : AppCompatActivity(),
    SidebarAdapter.Listener,
    ItemViewFragment.OnItemFragmentInteractionListener,
    ItemAttachmentEntry.OnAttachmentFragmentInteractionListener,
    NoteInteractionListener,
    LibraryScreen {

    private lateinit var viewModel: LibraryViewModel
    private var itemView: ItemViewFragment? = null

    lateinit var navController: NavController
    lateinit var navHostFragment: NavHostFragment

    private lateinit var libraryListViewModel: LibraryListViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        // white status bar icons: the toolbar colour shows through the status bar
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

        super.onCreate(savedInstanceState)

        // Without a stored login the library can't be built (its API client needs
        // the API key). MainActivity checks this, but the activity is also reachable
        // from recents or its exported filter intent after the login was removed.
        if (!AuthenticationStorageImpl(this).hasCredentials()) {
            startActivity(Intent(this, SyncSetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_library)

        // Back closes the sidebar, else steps back through the library (Back twice at
        // the top exits). A callback rather than onBackPressed(): with targetSdk 36,
        // Android 16 no longer calls onBackPressed() and Back would just close the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val drawer = findViewById<DrawerLayout>(R.id.drawerlayout_library)
                if (drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawer(GravityCompat.START)
                } else if (libraryListViewModel.isSelecting()) {
                    libraryListViewModel.changeSelection { clear() }
                } else {
                    viewModel.backButtonPressed()
                }
            }
        })

        setupToolbar()

        libraryListViewModel = ViewModelProvider(this).get(LibraryListViewModel::class.java)

        navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_container) as NavHostFragment
        navController = navHostFragment.navController

        this.setupActionbar()

        initUI()
        viewModel = ViewModelProvider(this).get(LibraryViewModel::class.java)
        viewModel.commands.attach(this, this)
        viewModel.attach(
            libraryListViewModel,
            ViewModelProvider(this).get(LibraryLoadingScreenViewModel::class.java)
        )

        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.libraryListFragment -> {
                    libraryListScreenShown()
                }

                R.id.libraryLoadingScreen -> {
                    libraryLoadingScreenShown()
                }

                else -> {
                    throw (NotImplementedError("Error screen $destination not handled."))
                }
            }
        }

        // LibraryViewModel's state outlives this activity; a recreated one just renders it
        viewModel.title.observe(this) { showTitle() }
        viewModel.subtitle.observe(this) { showTitle() }
        libraryListViewModel.getSelection().observe(this) { showSelectionMode(!it.isEmpty()) }
        viewModel.sidebar.observe(this) { showSidebar(it) }
        viewModel.pendingEdits.observe(this) { showPendingEdits(it) }
        viewModel.syncStatus.observe(this) { showSyncStatus(it) }
        viewModel.downloadsActive.observe(this) { invalidateOptionsMenu() }
        viewModel.screen.observe(this) {
            showScreen(
                when (it!!) {
                    AvailableScreens.LIBRARY_LOADING_SCREEN -> R.id.libraryLoadingScreen
                    AvailableScreens.LIBRARY_LISTVIEW_SCREEN -> R.id.libraryListFragment
                }
            )
        }
    }

    private var selecting = false

    /**
     * The toolbar while items are selected: their count for a title, ✕ to clear them in
     * place of the drawer's button, and the bulk actions in the menu. The drawer is locked
     * shut, so the list (and the selection) stay where they are.
     */
    private fun showSelectionMode(on: Boolean) {
        if (on != selecting) {
            selecting = on
            findViewById<DrawerLayout>(R.id.drawerlayout_library).setDrawerLockMode(
                if (on) DrawerLayout.LOCK_MODE_LOCKED_CLOSED else DrawerLayout.LOCK_MODE_UNLOCKED
            )
            mDrawerToggle.isDrawerIndicatorEnabled = !on
            // the drawer's button names itself again when it comes back
            if (on) supportActionBar?.setHomeActionContentDescription(R.string.clear_selection)
            invalidateOptionsMenu()
        }
        showTitle()
    }

    private fun showTitle() {
        val actionBar = supportActionBar ?: return
        if (selecting) {
            val count = libraryListViewModel.getSelection().value!!.size
            actionBar.title = resources.getQuantityString(R.plurals.selected_items, count, count)
            actionBar.subtitle = null
        } else {
            actionBar.title = viewModel.title.value
            actionBar.subtitle = viewModel.subtitle.value
        }
    }

    private fun libraryLoadingScreenShown() {
        mDrawerToggle.isDrawerIndicatorEnabled = !selecting
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }

    private fun libraryListScreenShown() {
        mDrawerToggle.isDrawerIndicatorEnabled = !selecting
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private val sidebarAdapter = SidebarAdapter(this)
    private lateinit var sidebar: RecyclerView

    private fun initUI() {
        setupActionbar()
        sidebar = findViewById(R.id.sidebar)
        sidebar.layoutManager = LinearLayoutManager(this)
        sidebar.adapter = sidebarAdapter
        // A closed drawer is still laid out, rows and all, in the first frame, which then
        // waits for them. The rows are made once the main thread is idle after it, or when
        // the drawer starts to open, whichever comes first.
        sidebar.visibility = View.GONE
        val showSidebarRows = { sidebar.visibility = View.VISIBLE }
        Looper.myQueue().addIdleHandler { showSidebarRows(); false }
        findViewById<DrawerLayout>(R.id.drawerlayout_library).addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerStateChanged(newState: Int) {
                if (newState != DrawerLayout.STATE_IDLE) showSidebarRows()
            }
        })
        val statusBarStrip = findViewById<View>(R.id.sidebar_status_bar)
        val padding = sidebar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.sidebar_container)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarStrip.layoutParams.height = bars.top
            statusBarStrip.requestLayout()
            sidebar.updatePadding(top = bars.top + padding, bottom = bars.bottom + padding)
            insets
        }
    }

    /**
     * Shows [destination], the list or the loading screen, as the only screen: navigating
     * pushed a new fragment each time (list, loading, list again at every start, more at
     * every sync) on a back stack nothing pops; Back is ours (handleOnBackPressed).
     */
    private fun showScreen(destination: Int) {
        if (navController.currentDestination?.id == destination) return
        navController.navigate(
            destination, null,
            NavOptions.Builder().setPopUpTo(navController.graph.id, true).build()
        )
    }

    private var sidebarSelection: String? = null

    /**
     * Redraws the sidebar; see [SidebarRows]. When the selection moved (Back, a search
     * result), scrolls the new one into view; expanding or collapsing doesn't scroll.
     */
    fun showSidebar(rows: List<SidebarRow>) {
        val selected = rows.indexOfFirst { it.isCurrent }
        val selection = rows.getOrNull(selected)?.id
        val moved = selection != sidebarSelection
        sidebarSelection = selection
        sidebarAdapter.submitList(rows) {
            if (!moved || selected < 0) return@submitList
            (sidebar.layoutManager as LinearLayoutManager).let {
                if (selected < it.findFirstCompletelyVisibleItemPosition() ||
                    selected > it.findLastCompletelyVisibleItemPosition()
                ) {
                    it.scrollToPosition(selected)
                }
            }
        }
    }

    private fun closeSidebar() {
        findViewById<DrawerLayout>(R.id.drawerlayout_library).closeDrawer(GravityCompat.START)
    }

    override fun onLibrarySelected(groupId: Int) {
        closeSidebar()
        viewModel.openLibrary(groupId)
    }

    override fun onGroupsToggled() {
        viewModel.toggleSidebarGroups()
    }

    override fun onCollectionSelected(key: String) {
        closeSidebar()
        viewModel.setCollection(key)
    }

    override fun onCollectionToggled(key: String) {
        viewModel.toggleSidebarCollection(key)
    }

    override fun onViewSelected(view: SidebarView) {
        closeSidebar()
        when (view) {
            SidebarView.UNFILED -> viewModel.setCollection(view.collectionId)
            SidebarView.MY_PUBLICATIONS -> viewModel.openMyPublications()
            SidebarView.TRASH -> viewModel.openTrash()
        }
    }

    fun showFilterMenu() {
        LibrarySortDialog(this, viewModel.currentLibraryId()) { viewModel.redisplayItems() }.show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (mDrawerToggle.onOptionsItemSelected(item)) {
            return true
        }

        when (item.itemId) {
            R.id.selection_add_tag -> {
                val count = libraryListViewModel.getSelection().value!!.size
                val suggest = SelectionDialogs(this).addTag(count) { viewModel.editSelectedTags(it, add = true) }
                viewModel.loadTags { tags -> suggest(tags.map { it.first }) }
            }
            R.id.selection_remove_tag -> viewModel.loadSelectedTags { tags ->
                if (isFinishing || isDestroyed) return@loadSelectedTags
                val count = libraryListViewModel.getSelection().value!!.size
                SelectionDialogs(this).removeTag(count, tags) { viewModel.editSelectedTags(it, add = false) }
            }
            R.id.selection_add_to_collection -> {
                val count = libraryListViewModel.getSelection().value!!.size
                SelectionDialogs(this).addToCollection(count, viewModel.collectionsForPicker()) {
                    viewModel.editSelectedCollection(it.key, it.name, add = true)
                }
            }
            R.id.selection_remove_from_collection -> viewModel.currentCollection()?.let { (key, name) ->
                val count = libraryListViewModel.getSelection().value!!.size
                SelectionDialogs(this).removeFromCollection(count, name) {
                    viewModel.editSelectedCollection(key, name, add = false)
                }
            }
            R.id.selection_download -> viewModel.countSelectedAttachments { counts ->
                if (isFinishing || isDestroyed) return@countSelectedAttachments
                SelectionDialogs(this).download(counts) { missing ->
                    if (missing.isEmpty()) makeToastAlert("They're all on this device already.")
                    else viewModel.downloadAttachments(missing)
                }
            }
            R.id.selection_delete_files -> viewModel.countSelectedAttachments { counts ->
                if (isFinishing || isDestroyed) return@countSelectedAttachments
                SelectionDialogs(this).deleteFiles(counts) { files ->
                    viewModel.findEditedFiles(files) { edited ->
                        if (isFinishing || isDestroyed) return@findEditedFiles
                        SelectionDialogs(this).confirmDelete(files, edited) { viewModel.deleteLocalFiles(it) }
                    }
                }
            }
            R.id.stop_downloads -> viewModel.stopDownloads()
            R.id.select_all -> libraryListViewModel.changeSelection { selectAll(it) }
            R.id.invert_selection -> libraryListViewModel.changeSelection { invert(it) }
            R.id.clear_selection -> libraryListViewModel.changeSelection { clear() }

            R.id.filter_menu -> {
                showFilterMenu()
            }

            R.id.search -> {
                viewModel.warmSearchIndex()
                LibrarySearchDialog(this, viewModel.currentSearch()) { viewModel.search(it) }.show()
            }

            R.id.library_filter -> {
                viewModel.loadTags { tags ->
                    if (isFinishing || isDestroyed) return@loadTags
                    LibraryFilterDialog(this, viewModel.getLibraryFilter(), tags) {
                        viewModel.setLibraryFilter(it)
                    }.show()
                }
            }

            R.id.pending_edits -> {
                viewModel.requestLibraryRefresh()
            }

            R.id.upload_edited_attachments -> {
                viewModel.uploadEditedAttachments()
            }

            R.id.settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            }

            R.id.force_resync -> {
                // clears every table: pending tag edits and new notes (PendingItemEdit)
                // are gone, and with the attachment bases edited files no longer count
                // as edited
                createYesNoPrompt(
                    "Re-download the library?",
                    "This deletes the library data on this device; the next sync downloads " +
                            "all of it again from Zotero. Downloaded files stay.\n\n" +
                            "Edits waiting to sync are lost: new notes and tag changes are " +
                            "deleted, and edited files are no longer seen as edited. Sync first.",
                    "Re-download", "Cancel", { viewModel.requestForceResync() }, {}
                )
            }

            android.R.id.home -> {
                // ✕ while selecting; Back clears the selection then
                onBackPressedDispatcher.onBackPressed()
            }

        }
        return super.onOptionsItemSelected(item)
    }

    private var pendingEdits = 0

    /** Edited attachments waiting to go up; shown in the toolbar, tapping it syncs. */
    fun showPendingEdits(count: Int) {
        if (count == pendingEdits) return
        pendingEdits = count
        invalidateOptionsMenu()
    }

    // the toolbar's "Syncing n/m" while a sync runs, else null; in place of the edit count
    private var syncLabel: String? = null
    private var syncMenuItem: MenuItem? = null

    private fun showSyncStatus(status: SyncStatus?) {
        val label = status?.takeIf { it.running }?.let { running ->
            running.itemProgress()?.let { (done, total) -> getString(R.string.syncing_progress, done, total) }
                ?: getString(R.string.syncing)
        }
        if ((label == null) != (syncLabel == null)) {
            // The display stays on while a sync runs: once it sleeps, Doze cuts the
            // network off within minutes and a first sync of a large library stops.
            if (label != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            syncLabel = label
            invalidateOptionsMenu()
        } else if (label != syncLabel) {
            // only the count: rebuilding the menu for each page would close it while open
            syncLabel = label
            syncMenuItem?.title = label
        }
    }

    /** Attachment keys whose file is on this device / has edits to sync, for the list's PDF icons. */
    override fun showAttachmentStates(onDevice: Set<String>, edited: Set<String>) {
        libraryListViewModel.setAttachmentStates(AttachmentStates(onDevice, edited))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(
            if (selecting) R.menu.library_selection else R.menu.activity_library_actionbar, menu
        )
        return true
    }

    // here rather than in onCreateOptionsMenu: LibraryListFragment re-inflates the menu after it
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.setGroupVisible(R.id.selection_edits, viewModel.canEditItems())
        menu.findItem(R.id.stop_downloads)?.isVisible = viewModel.downloadsActive.value == true
        menu.findItem(R.id.selection_remove_from_collection)?.isVisible =
            viewModel.canEditItems() && viewModel.currentCollection() != null
        syncMenuItem = menu.findItem(R.id.sync_status)?.apply {
            isVisible = syncLabel != null
            syncLabel?.let { title = it }
        }
        menu.findItem(R.id.pending_edits)?.apply {
            // while a sync runs the edits are going up; its details list them
            isVisible = pendingEdits > 0 && syncLabel == null
            title = resources.getQuantityString(R.plurals.pending_edits, pendingEdits, pendingEdits)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun createErrorAlert(title: String, message: String, onClick: () -> Unit) {
        val alert = AlertDialog.Builder(this)
        alert.setIcon(R.drawable.ic_error_black_24dp)
        alert.setTitle(title)
        alert.setMessage(message)
        alert.setPositiveButton("OK") { _, _ -> onClick() }
        try {
            alert.show()
        } catch (exception: WindowManager.BadTokenException) {
            Log.e("zotero", "error cannot show error dialog. ${message}")
        }
    }

    lateinit var mDrawerToggle: ActionBarDrawerToggle
    private fun setupActionbar() {
        val drawer = findViewById<DrawerLayout>(R.id.drawerlayout_library)
        mDrawerToggle = ActionBarDrawerToggle(
            this,
            drawer,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )

        mDrawerToggle.isDrawerIndicatorEnabled = true
        mDrawerToggle.isDrawerSlideAnimationEnabled = true
        // the button while items are selected (showSelectionMode)
        mDrawerToggle.setHomeAsUpIndicator(R.drawable.baseline_close_24)

        drawer.addDrawerListener(mDrawerToggle)
        mDrawerToggle.syncState()

        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.show()
    }

    override fun onPostCreate(savedInstanceState: Bundle?, persistentState: PersistableBundle?) {
        super.onPostCreate(savedInstanceState, persistentState)
        mDrawerToggle.syncState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mDrawerToggle.syncState()
    }

    override fun showItemDialog(
    ) {
        itemView = ItemViewFragment()
        val fm = supportFragmentManager
        itemView?.show(fm, "ItemDialog")

    }

    override fun showNote(note: Note) {
        val noteView = NoteView(this, note, this)
        noteView.show()
    }

    override fun closeItemView() {
        itemView?.dismiss()
        itemView = null
    }

    override fun showLoadingAlertDialog(message: String) {
        if (progressDialog == null) {
            progressDialog = ProgressDialog(this)
        }
        progressDialog?.isIndeterminate = true
        progressDialog?.setMessage(message)
        progressDialog?.show()

    }

    override fun hideLoadingAlertDialog() {
        progressDialog?.hide()
        progressDialog = null
    }

    @Suppress("DEPRECATION")
    var progressDialog: ProgressDialog? = null

    @Suppress("DEPRECATION")
    override fun updateAttachmentDownloadProgress(progress: Int, total: Int) {
        if (progressDialog == null) {
            progressDialog = ProgressDialog(this)
            progressDialog?.setTitle("Downloading")
            progressDialog?.setButton("Cancel") { dialogInterface, _ ->
                viewModel.cancelAttachmentDownload()
            }
        }
        if (true) { // if (total == 0) {
            progressDialog?.isIndeterminate = true
            val progressString = if (progress == 0) {
                ""
            } else {
                if (total > 0) {
                    "$progress/${total}KB"
                } else {
                    "${progress}KB"
                }
            }

            progressDialog?.setMessage(progressString.ifEmpty { "Starting…" })
        }
        progressDialog?.show()
    }

    override fun hideAttachmentDownloadProgress() {
        progressDialog?.hide()
        progressDialog = null
    }

    var uploadProgressDialog: ProgressDialog? = null
    override fun showAttachmentUploadProgress(attachment: Item, position: Int, total: Int) {
        if (uploadProgressDialog == null) {
            uploadProgressDialog = ProgressDialog(this)
            uploadProgressDialog?.isIndeterminate = true
            uploadProgressDialog?.setCancelable(false)
        }
        uploadProgressDialog?.setTitle(
            if (total == 1) "Uploading" else "Uploading $position of $total"
        )
        uploadProgressDialog?.setMessage(
            "${attachment.data["filename"] ?: attachment.getTitle()}\n\nKeep the app open until it finishes."
        )
        uploadProgressDialog?.show()
    }

    override fun hideAttachmentUploadProgress() {
        uploadProgressDialog?.dismiss()
        uploadProgressDialog = null
    }

    override fun createYesNoPrompt(
        title: String,
        message: String, yesText: String, noText: String,
        onYesClick: () -> Unit,
        onNoClick: () -> Unit
    ) {
        val alert = AlertDialog.Builder(this)
        alert.setTitle(title)
        alert.setMessage(message)
        alert.setPositiveButton(yesText) { _, _ -> onYesClick() }
        alert.setNegativeButton(noText) { _, _ -> onNoClick() }
        try {
            alert.show()
        } catch (exception: WindowManager.BadTokenException) {
            Log.e("zotero", "error creating window bro")
        }
    }

    /** Edited attachments to upload, all ticked; [onUpload] gets the ticked indices. */
    override fun chooseAttachmentsToUpload(labels: List<String>, onUpload: (List<Int>) -> Unit) {
        val checked = BooleanArray(labels.size) { true }
        val alert = AlertDialog.Builder(this)
        alert.setTitle(
            if (labels.size == 1) "Upload your edited attachment?"
            else "Upload ${labels.size} edited attachments?"
        )
        alert.setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
            checked[which] = isChecked
        }
        alert.setPositiveButton("Upload") { _, _ ->
            onUpload(checked.indices.filter { checked[it] })
        }
        alert.setNegativeButton("Later") { _, _ -> }
        try {
            alert.show()
        } catch (exception: WindowManager.BadTokenException) {
            Log.e("zotero", "cannot show upload dialog")
        }
    }

    /* Is called by the fragment when an attachment is openned by the user. */
    override fun openAttachmentFileListener(item: Item) {
        viewModel.openAttachment(item)
    }


    override fun describeAttachment(item: Item, onState: (AttachmentLocalState) -> Unit) {
        viewModel.describeAttachment(item, onState)
    }

    /**
     * Long-press menu for an attachment, from the library list's PDF icon and from the
     * item view. A plain tap opens it (fetching Zotero's latest version first).
     */
    override fun showAttachmentMenu(item: Item) {
        val actions = listOf<Pair<String, () -> Unit>>(
            "Open" to { viewModel.openAttachment(item) },
            "Download" to { viewModel.downloadAttachmentOnly(item) },
            "Upload changes" to { viewModel.uploadChanges(item) },
            "Delete from this device" to { viewModel.deleteLocalAttachment(item) },
        )
        try {
            AlertDialog.Builder(this)
                .setTitle(item.data["filename"] ?: item.getTitle())
                .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                    actions[which].second()
                }.show()
        } catch (exception: WindowManager.BadTokenException) {
            Log.e("zotero", "cannot show attachment menu")
        }
    }

    override fun openLinkedAttachmentListener(item: Item) {
        viewModel.openAttachment(item)
    }


    override fun onListFragmentInteraction(item: Item?) {
        Log.d("zotero", "got onListFragmentInteraction from item ${item?.itemKey}")
    }

    override fun makeToastAlert(message: String) {
        val toast = Toast.makeText(this, message, Toast.LENGTH_LONG)
        toast.show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(packageName, "got intent ${intent.action} $intent")
    }

    override fun onPause() {
        Log.e("zotero", "paused")
        super.onPause()
    }

    override fun onPostResume() {
        super.onPostResume()
        viewModel.onResume()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        Log.e("zotero", "restored!")
    }

    override fun onResume() {
        super.onResume()
        Log.d("zotero", "onResume called.")
    }

    override fun onNoteAdd(item: Item, text: String) = viewModel.addNote(item, text)

    override fun onNoteEdit(note: Note) {
        viewModel.modifyNote(note)
    }

    override fun onNoteDelete(note: Note) {
        viewModel.deleteNote(note)
    }

    override fun deleteNote(note: Note) {
        viewModel.deleteNote(note)
    }

    override fun editNote(note: Note) {
        viewModel.modifyNote(note)
    }

    override fun onTagAdd(item: Item, tag: String) = viewModel.editTag(item, tag, add = true)

    override fun onTagRemove(item: Item, tag: String) = viewModel.editTag(item, tag, add = false)

    override fun canEditItems(): Boolean = viewModel.canEditItems()

    override fun libraryTags(onResult: (List<String>) -> Unit) =
        viewModel.loadTags { tags -> onResult(tags.map { it.first }) }

    override fun hasPendingEdits(item: Item): Boolean = viewModel.hasPendingEdits(item)

    // this  lets keyboard close when clicked in backgroud
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(
                        v.windowToken,
                        0
                    )
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
    }
}

enum class AvailableScreens {
    LIBRARY_LOADING_SCREEN,
    LIBRARY_LISTVIEW_SCREEN
}