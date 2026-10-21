package com.mickstarify.zooforzotero.LibraryActivity

import android.app.ProgressDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
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
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.getSystemService
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mickstarify.zooforzotero.AttachmentManager.AttachmentManager
import com.mickstarify.zooforzotero.LibraryActivity.ItemView.ItemAttachmentEntry
import com.mickstarify.zooforzotero.LibraryActivity.ItemView.ItemViewFragment
import com.mickstarify.zooforzotero.LibraryActivity.Notes.NoteInteractionListener
import com.mickstarify.zooforzotero.LibraryActivity.Notes.NoteView
import com.mickstarify.zooforzotero.LibraryActivity.ViewModels.AttachmentStates
import com.mickstarify.zooforzotero.LibraryActivity.ViewModels.LibraryListViewModel
import com.mickstarify.zooforzotero.R
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
    NoteInteractionListener {

    lateinit var presenter: LibraryActivityPresenter
    private var itemView: ItemViewFragment? = null

    lateinit var navController: NavController
    lateinit var navHostFragment: NavHostFragment

    private lateinit var libraryListViewModel: LibraryListViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        // white status bar icons: the toolbar colour shows through the status bar
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

        super.onCreate(savedInstanceState)
        
        setContentView(R.layout.activity_library)

        setupToolbar()

        libraryListViewModel = ViewModelProvider(this).get(LibraryListViewModel::class.java)

        navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_container) as NavHostFragment
        navController = navHostFragment.navController

        this.setupActionbar()

        presenter = ViewModelProvider(this).get(LibraryViewModel::class.java).presenter
        presenter.attach(this)

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

        // the presenter's state outlives this activity; a recreated one just renders it
        presenter.title.observe(this) { supportActionBar?.title = it }
        presenter.subtitle.observe(this) { supportActionBar?.subtitle = it }
        presenter.sidebar.observe(this) { showSidebar(it) }
        presenter.pendingEdits.observe(this) { showPendingEdits(it) }
    }

    private fun libraryLoadingScreenShown() {
        mDrawerToggle.isDrawerIndicatorEnabled = true
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }

    private fun libraryListScreenShown() {
        mDrawerToggle.isDrawerIndicatorEnabled = true
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private val sidebarAdapter = SidebarAdapter(this)
    private lateinit var sidebar: RecyclerView

    fun initUI() {
        setupActionbar()
        sidebar = findViewById(R.id.sidebar)
        sidebar.layoutManager = LinearLayoutManager(this)
        sidebar.adapter = sidebarAdapter
        val statusBarStrip = findViewById<View>(R.id.sidebar_status_bar)
        val padding = sidebar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.sidebar_container)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarStrip.layoutParams.height = bars.top
            statusBarStrip.requestLayout()
            sidebar.updatePadding(top = bars.top + padding, bottom = bars.bottom + padding)
            insets
        }
        navController.navigate(R.id.libraryListFragment)
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
        presenter.openLibrary(groupId)
    }

    override fun onGroupsToggled() {
        presenter.toggleSidebarGroups()
    }

    override fun onCollectionSelected(key: String) {
        closeSidebar()
        presenter.setCollection(key)
    }

    override fun onCollectionToggled(key: String) {
        presenter.toggleSidebarCollection(key)
    }

    override fun onViewSelected(view: SidebarView) {
        closeSidebar()
        when (view) {
            SidebarView.UNFILED -> presenter.setCollection(view.collectionId)
            SidebarView.MY_PUBLICATIONS -> presenter.openMyPublications()
            SidebarView.TRASH -> presenter.openTrash()
        }
    }

    fun showFilterMenu() {
        LibrarySortDialog(this) { presenter.redisplayItems() }.show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (mDrawerToggle.onOptionsItemSelected(item)) {
            return true
        }

        when (item.itemId) {
            R.id.filter_menu -> {
                showFilterMenu()
            }

            R.id.library_filter -> {
                LibraryFilterDialog(this, presenter.getLibraryFilter()) {
                    presenter.previewLibraryFilter(it)
                }.show()
            }

            R.id.pending_edits -> {
                presenter.requestLibraryRefresh()
            }

            R.id.upload_edited_attachments -> {
                presenter.uploadEditedAttachments()
            }

            R.id.settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            }

            R.id.attachment_manager -> {
                val intent = Intent(this, AttachmentManager::class.java)
                startActivity(intent)
            }

            R.id.force_resync -> {
                presenter.requestForceResync()
            }

            android.R.id.home -> {
                onBackPressed()
            }

        }
        return super.onOptionsItemSelected(item)
    }

    lateinit var searchView: SearchView
    private var pendingEdits = 0

    /** Edited attachments waiting to go up; shown in the toolbar, tapping it syncs. */
    fun showPendingEdits(count: Int) {
        if (count == pendingEdits) return
        pendingEdits = count
        invalidateOptionsMenu()
    }

    /** Attachment keys whose file is on this device / has edits to sync, for the list's PDF icons. */
    fun showAttachmentStates(onDevice: Set<String>, edited: Set<String>) {
        libraryListViewModel.setAttachmentStates(AttachmentStates(onDevice, edited))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.activity_library_actionbar, menu)
        return true
    }

    // here rather than in onCreateOptionsMenu: LibraryListFragment re-inflates the menu after it
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.pending_edits)?.apply {
            isVisible = pendingEdits > 0
            title = resources.getQuantityString(R.plurals.pending_edits, pendingEdits, pendingEdits)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    fun createErrorAlert(title: String, message: String, onClick: () -> Unit) {
        val alert = AlertDialog.Builder(this)
        alert.setIcon(R.drawable.ic_error_black_24dp)
        alert.setTitle(title)
        alert.setMessage(message)
        alert.setPositiveButton("Ok") { _, _ -> onClick() }
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

    fun showItemDialog(
    ) {
        itemView = ItemViewFragment()
        val fm = supportFragmentManager
        itemView?.show(fm, "ItemDialog")

    }

    fun showNote(note: Note) {
        val noteView = NoteView(this, note, this)
        noteView.show()
    }

    fun closeItemView() {
        itemView?.dismiss()
        itemView = null
    }

    fun showLoadingAlertDialog(message: String) {
        if (progressDialog == null) {
            progressDialog = ProgressDialog(this)
        }
        progressDialog?.isIndeterminate = true
        progressDialog?.setMessage(message)
        progressDialog?.show()

    }

    fun hideLoadingAlertDialog() {
        progressDialog?.hide()
        progressDialog = null
    }

    @Suppress("DEPRECATION")
    var progressDialog: ProgressDialog? = null

    @Suppress("DEPRECATION")
    fun updateAttachmentDownloadProgress(progress: Int, total: Int) {
        if (progressDialog == null) {
            progressDialog = ProgressDialog(this)
            progressDialog?.setTitle("Downloading File")
            progressDialog?.setButton("Cancel") { dialogInterface, _ ->
                presenter.cancelAttachmentDownload()
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

            progressDialog?.setMessage("Downloading your Attachment. $progressString")
        }
        progressDialog?.show()
    }

    fun hideAttachmentDownloadProgress() {
        progressDialog?.hide()
        progressDialog = null
    }

    var uploadProgressDialog: ProgressDialog? = null
    fun showAttachmentUploadProgress(attachment: Item, position: Int, total: Int) {
        if (uploadProgressDialog == null) {
            uploadProgressDialog = ProgressDialog(this)
            uploadProgressDialog?.isIndeterminate = true
            uploadProgressDialog?.setCancelable(false)
        }
        uploadProgressDialog?.setTitle(
            if (total == 1) "Uploading Attachment" else "Uploading $position of $total"
        )
        uploadProgressDialog?.setMessage(
            "Uploading ${attachment.data["filename"]}. " +
                    "This may take a while, do not close the app."
        )
        uploadProgressDialog?.show()
    }

    fun hideAttachmentUploadProgress() {
        uploadProgressDialog?.dismiss()
        uploadProgressDialog = null
    }

    fun createYesNoPrompt(
        title: String,
        message: String, yesText: String, noText: String,
        onYesClick: () -> Unit,
        onNoClick: () -> Unit
    ) {
        val alert = AlertDialog.Builder(this)
        alert.setIcon(R.drawable.ic_error_black_24dp)
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
    fun chooseAttachmentsToUpload(labels: List<String>, onUpload: (List<Int>) -> Unit) {
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
        presenter.openAttachment(item)
    }


    override fun describeAttachment(item: Item, onState: (AttachmentLocalState) -> Unit) {
        presenter.describeAttachment(item, onState)
    }

    /**
     * Long-press menu for an attachment, from the library list's PDF icon and from the
     * item view. A plain tap opens it (fetching Zotero's latest version first).
     */
    override fun showAttachmentMenu(item: Item) {
        val actions = listOf<Pair<String, () -> Unit>>(
            "Open" to { presenter.openAttachment(item) },
            "Download to device" to { presenter.downloadAttachmentOnly(item) },
            "Upload my changes" to { presenter.uploadChanges(item) },
            "Delete local copy" to { presenter.deleteLocalAttachment(item) },
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
        presenter.openAttachment(item)
    }


    override fun onListFragmentInteraction(item: Item?) {
        Log.d("zotero", "got onListFragmentInteraction from item ${item?.itemKey}")
    }

    fun makeToastAlert(message: String) {
        val toast = Toast.makeText(this, message, Toast.LENGTH_LONG)
        toast.show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(packageName, "got intent ${intent.action} $intent")
        if (intent.action == ACTION_FILTER) {
            val query = intent.getStringExtra(EXTRA_QUERY) ?: ""
            Log.d(packageName, "got intent for library filter $query")
            presenter.filterEntries(query)
        }
    }

    override fun onPause() {
        Log.e("zotero", "paused")
        super.onPause()
    }

    override fun onPostResume() {
        super.onPostResume()
        presenter.onResume()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        Log.e("zotero", "restored!")
    }

    override fun onResume() {
        super.onResume()
        Log.d("zotero", "onResume called.")
    }

    override fun onNoteCreate(note: Note) {
        presenter.createNote(note)
    }

    override fun onNoteEdit(note: Note) {
        presenter.modifyNote(note)
    }

    override fun onNoteDelete(note: Note) {
        presenter.deleteNote(note)
    }

    private var doubleBackToExitPressedOnce = false
    override fun onBackPressed() {
        val drawer = findViewById<DrawerLayout>(R.id.drawerlayout_library)
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START)
            return
        }
        presenter.backButtonPressed()
        Handler().postDelayed(Runnable { doubleBackToExitPressedOnce = false }, 2000)
    }

    companion object {
        const val ACTION_FILTER =
            "com.mickstarify.zooforzotero.intent.action.LIBRARY_FILTER_INTENT"
        const val EXTRA_QUERY = "com.mickstarify.zooforzotero.intent.EXTRA_QUERY_TEXT"
    }

    override fun deleteNote(note: Note) {
        presenter.deleteNote(note)
    }

    override fun editNote(note: Note) {
        presenter.modifyNote(note)
    }

    override fun shareText(shareText: String) {
        /* Used to share zotero items. */

        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, null)
        startActivity(shareIntent)
    }

    override fun onTagOpenListener(tag: String) {
        presenter.onTagOpen(tag)
    }

    fun getCurrentScreen(): AvailableScreens {
        return when (navController.currentDestination?.id) {
            R.id.libraryListFragment -> AvailableScreens.LIBRARY_LISTVIEW_SCREEN
            R.id.libraryLoadingScreen -> AvailableScreens.LIBRARY_LOADING_SCREEN
            else -> throw (Exception("Unknown current screen ${navController.currentDestination}"))
        }
    }

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