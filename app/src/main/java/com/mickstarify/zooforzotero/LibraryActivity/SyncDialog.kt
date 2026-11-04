package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.common.DialogActions
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.SyncStatus

/**
 * The sync dialog, behind the toolbar's sync button in every state: the edits waiting to go up, the running or last sync stage by stage
 * (SyncDetails), the bulk downloads of attachments while they work (with Stop downloads),
 * and Sync now while no sync runs. Open while the user reads it, redrawn as the sync and
 * the downloads go on; when a sync ends, the edits are listed again (those left, if any). Laid
 * out like Manage attachments: a header over each part, Sync now and Close under them
 * (DialogActions).
 *
 * @param details what to say of a sync's status
 * @param lastSynced "Last finished sync: …" for the open library, when there's no status
 * @param editCount the number of edits waiting, for the title
 * @param describeEdits the edits waiting, a line per item, given to its argument on the main thread
 * @param syncNow starts a sync of the open library
 * @param stopDownloads stops the bulk downloads
 */
class SyncDialog(
    private val context: Context,
    private val details: (SyncStatus) -> SyncDetails,
    private val lastSynced: () -> String,
    private val editCount: () -> Int,
    private val describeEdits: ((List<String>) -> Unit) -> Unit,
    private val syncNow: () -> Unit,
    private val stopDownloads: () -> Unit,
) {
    private var dialog: AlertDialog? = null
    private lateinit var editsHeader: View
    private lateinit var editsText: TextView
    private lateinit var syncHeader: TextView
    private lateinit var syncText: TextView
    private lateinit var syncNowButton: Button
    private lateinit var downloadsHeader: View
    private lateinit var downloadsRow: View
    private lateinit var downloadsText: TextView
    private var status: SyncStatus? = null
    // null until read
    private var edits: List<String>? = null

    val isShowing get() = dialog != null

    /** Opens with the sync's [status] and the bulk downloads' progress [downloads] (see showDownloads). */
    fun show(status: SyncStatus?, downloads: String?) {
        this.status = status
        val builder = AlertDialog.Builder(context)
        val view = LayoutInflater.from(builder.context).inflate(R.layout.dialog_sync, null)
        editsHeader = view.findViewById(R.id.textView_sync_edits_header)
        editsText = view.findViewById(R.id.textView_sync_edits)
        syncHeader = view.findViewById(R.id.textView_sync_header)
        syncText = view.findViewById(R.id.textView_sync_stages)
        syncNowButton = view.findViewById(R.id.button_sync_now)
        downloadsHeader = view.findViewById(R.id.textView_downloads_header)
        downloadsRow = view.findViewById(R.id.layout_downloads)
        downloadsText = view.findViewById(R.id.textView_downloads)
        view.findViewById<Button>(R.id.button_stop_downloads).setOnClickListener { stopDownloads() }
        showDownloads(downloads)
        val d = builder
            .setTitle(" ")
            .setView(view)
            .setOnDismissListener { dialog = null }
            .create()
        DialogActions.keepButtonsInPlace(d)
        d.show()
        dialog = d
        DialogActions.leave(view, d, "Close")
        syncNowButton.setOnClickListener {
            // shown as running before the first status arrives
            it.visibility = View.GONE
            d.setTitle(context.getString(R.string.syncing))
            showEdits(null)
            syncHeader.text = THIS_SYNC
            syncText.text = "Starting…"
            syncNow()
        }
        render()
        readEdits()
    }

    /** A sync's status changed: redrawn; once it ends, the edits are read again. */
    fun update(status: SyncStatus) {
        val ended = this.status?.running == true && !status.running
        this.status = status
        if (dialog == null) return
        render()
        if (ended) readEdits()
    }

    /** The bulk downloads' progress ("Downloading 3 of 12"), or null when none are at work. */
    fun showDownloads(status: String?) {
        if (!this::downloadsText.isInitialized) return // not shown yet
        val visibility = if (status == null) View.GONE else View.VISIBLE
        downloadsHeader.visibility = visibility
        downloadsRow.visibility = visibility
        downloadsText.text = status
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    private fun readEdits() {
        describeEdits { lines ->
            edits = lines
            render()
        }
    }

    private fun render() {
        val d = dialog ?: return
        val status = status
        val running = status?.running == true
        val details = status?.let(details)
        val count = editCount()
        d.setTitle(
            when {
                running || (count == 0 && details != null) -> details!!.title
                count > 0 -> context.resources.getQuantityString(R.plurals.pending_edits, count, count)
                else -> "Sync"
            }
        )
        showEdits(if (running) null else edits)
        syncHeader.text = if (running) THIS_SYNC else LAST_SYNC
        syncText.text = details?.message ?: lastSynced()
        syncNowButton.visibility = if (running) View.GONE else View.VISIBLE
    }

    // the edits waiting under their header; none (or not read yet, or being sent): neither
    private fun showEdits(edits: List<String>?) {
        val visibility = if (edits.isNullOrEmpty()) View.GONE else View.VISIBLE
        editsHeader.visibility = visibility
        editsText.visibility = visibility
        editsText.text = editLines(edits.orEmpty())
    }

    companion object {
        // lines of edits shown; a bulk edit can make thousands
        const val MAX_EDIT_LINES = 100

        private const val THIS_SYNC = "This sync"
        private const val LAST_SYNC = "Last sync"

        /** The [edits] waiting, a line per item, at most MAX_EDIT_LINES. */
        fun editLines(edits: List<String>): String {
            val shown = edits.take(MAX_EDIT_LINES).joinToString("\n") { "• $it" }
            val more = edits.size - MAX_EDIT_LINES
            return if (more > 0) "$shown\n…and %,d more items.".format(more) else shown
        }
    }
}
