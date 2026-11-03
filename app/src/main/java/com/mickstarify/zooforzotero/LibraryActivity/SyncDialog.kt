package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.SyncStatus

/**
 * The sync dialog, behind both of the toolbar's sync buttons ("N edits to sync" and
 * "Syncing 45%"): the edits waiting to go up, the running or last sync stage by stage
 * (SyncDetails), and Sync now while no sync runs. Open while the user reads it, redrawn as
 * the sync goes on; when it ends, the edits are listed again (those left, if any). Laid
 * out like Manage attachments: a header over each part, Sync now a full-width button,
 * Close alone in the bar.
 *
 * @param details what to say of a sync's status
 * @param lastSynced "Last finished sync: …" for the open library, when there's no status
 * @param editCount the number of edits waiting, for the title
 * @param describeEdits the edits waiting, a line per item, given to its argument on the main thread
 * @param syncNow starts a sync of the open library
 */
class SyncDialog(
    private val context: Context,
    private val details: (SyncStatus) -> SyncDetails,
    private val lastSynced: () -> String,
    private val editCount: () -> Int,
    private val describeEdits: ((List<String>) -> Unit) -> Unit,
    private val syncNow: () -> Unit,
) {
    private var dialog: AlertDialog? = null
    private lateinit var editsHeader: View
    private lateinit var editsText: TextView
    private lateinit var syncHeader: TextView
    private lateinit var syncText: TextView
    private lateinit var syncNowButton: Button
    private var status: SyncStatus? = null
    // null until read
    private var edits: List<String>? = null

    val isShowing get() = dialog != null

    fun show(status: SyncStatus?) {
        this.status = status
        val builder = AlertDialog.Builder(context)
        val view = LayoutInflater.from(builder.context).inflate(R.layout.dialog_sync, null)
        editsHeader = view.findViewById(R.id.textView_sync_edits_header)
        editsText = view.findViewById(R.id.textView_sync_edits)
        syncHeader = view.findViewById(R.id.textView_sync_header)
        syncText = view.findViewById(R.id.textView_sync_stages)
        syncNowButton = view.findViewById(R.id.button_sync_now)
        val d = builder
            .setTitle(" ")
            .setView(view)
            .setNegativeButton("Close", null)
            .setOnDismissListener { dialog = null }
            .show()
        dialog = d
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
