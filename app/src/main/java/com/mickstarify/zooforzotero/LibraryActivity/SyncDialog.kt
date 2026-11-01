package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.ZoteroAPI.Syncing.SyncStatus

/**
 * The sync dialog, behind both of the toolbar's sync buttons ("N edits to sync" and
 * "Syncing 45%"): the edits waiting to go up, the running or last sync stage by stage
 * (SyncDetails), and Sync now while no sync runs. Open while the user reads it, redrawn as
 * the sync goes on; when it ends, the edits are listed again (those left, if any).
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
    private var status: SyncStatus? = null
    // null until read
    private var edits: List<String>? = null

    val isShowing get() = dialog != null

    fun show(status: SyncStatus?) {
        this.status = status
        val d = AlertDialog.Builder(context)
            .setTitle(" ")
            .setMessage(" ") // set now, so the message can change once it's shown
            .setPositiveButton("Sync now", null) // set below: the button would close the dialog
            .setNegativeButton("Close", null)
            .setOnDismissListener { dialog = null }
            .show()
        dialog = d
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            // shown as running before the first status arrives
            it.visibility = View.GONE
            d.setTitle(context.getString(R.string.syncing))
            d.setMessage("Starting…")
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
        d.setMessage(message(if (running) null else edits, details?.message ?: lastSynced()))
        d.getButton(AlertDialog.BUTTON_POSITIVE).visibility = if (running) View.GONE else View.VISIBLE
    }

    companion object {
        // lines of edits shown; a bulk edit can make thousands
        const val MAX_EDIT_LINES = 100

        /**
         * The dialog's text: the [edits] waiting (a line per item, at most MAX_EDIT_LINES;
         * null while they're being read or a sync sends them), then the [sync]'s stages or
         * when the library last synced.
         */
        fun message(edits: List<String>?, sync: String): String {
            if (edits.isNullOrEmpty()) return sync
            val shown = edits.take(MAX_EDIT_LINES).joinToString("\n") { "• $it" }
            val more = edits.size - MAX_EDIT_LINES
            val list = if (more > 0) "$shown\n…and %,d more items.".format(more) else shown
            return "Waiting to sync:\n$list\n\n$sync"
        }
    }
}
