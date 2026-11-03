package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.common.DialogActions
import com.mickstarify.zooforzotero.common.DialogText
import com.mickstarify.zooforzotero.common.DialogText.files
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentCounts
import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentKind

/**
 * "Manage attachments" for the items selected in the list, or (from the main menu) all the
 * items the list shows: their stored files by kind
 * (PDFs, HTML snapshots, others), each with how many are on this device, ticked to be
 * acted on; Download fetches the ticked kinds' missing files in the background, Remove
 * takes their copies off this device (after asking). Both are full-width buttons under
 * the boxes, naming how many files they act on; Close is the only one in the button bar. While bulk downloads are at work it
 * says how far they are, with a button to stop them. Counts and lists only: as cheap for
 * the whole library as for one item.
 *
 * @param count the number of items acted on
 * @param listed the items are those the list shows, not a selection
 * @param counts the selection's stored files by kind (AttachmentCounts.of); kinds without any are left out
 * @param onDownload the ticked kinds' files not on this device
 * @param onRemove the ticked kinds' files on this device
 * @param onStop stops the bulk downloads
 */
class ManageAttachmentsDialog(
    private val context: Context,
    private val count: Int,
    private val listed: Boolean,
    private val counts: Map<AttachmentKind, AttachmentCounts>,
    private val onDownload: (List<Item>) -> Unit,
    private val onRemove: (List<Item>) -> Unit,
    private val onStop: () -> Unit,
) {
    private val ticked = counts.keys.toMutableSet()
    private var dialog: AlertDialog? = null
    private var downloadsHeader: View? = null
    private var downloadsRow: View? = null
    private var downloadsText: TextView? = null
    private var downloadButton: Button? = null
    private var removeButton: Button? = null

    private fun chosen(): AttachmentCounts =
        counts.filterKeys { it in ticked }.values.fold(AttachmentCounts(emptyList(), emptyList())) { a, b -> a + b }

    fun show() {
        val builder = AlertDialog.Builder(context)
        // the dialog's theme, for the rows' colours
        val themed = builder.context
        val view = LayoutInflater.from(themed).inflate(R.layout.dialog_manage_attachments, null)
        val scope = view.findViewById<TextView>(R.id.textView_attachments_scope)
        val hint = view.findViewById<TextView>(R.id.textView_attachments_hint)
        val kinds = view.findViewById<LinearLayout>(R.id.layout_attachment_kinds)
        downloadsHeader = view.findViewById(R.id.textView_downloads_header)
        downloadsRow = view.findViewById(R.id.layout_downloads)
        downloadsText = view.findViewById(R.id.textView_downloads)
        view.findViewById<Button>(R.id.button_stop_downloads).setOnClickListener { onStop() }
        // like the button bar's buttons, each closes the dialog as it acts
        downloadButton = view.findViewById<Button>(R.id.button_download_attachments).apply {
            setOnClickListener { dismiss(); chosen().missing.takeIf { it.isNotEmpty() }?.let(onDownload) }
        }
        removeButton = view.findViewById<Button>(R.id.button_remove_attachments).apply {
            setOnClickListener { dismiss(); chosen().onDevice.takeIf { it.isNotEmpty() }?.let(onRemove) }
        }
        if (counts.isEmpty()) {
            downloadButton?.visibility = View.GONE
            removeButton?.visibility = View.GONE
        }

        val all = counts.values.fold(AttachmentCounts(emptyList(), emptyList())) { a, b -> a + b }
        scope.text = DialogText.appliesTo(count, listed)
        hint.text = if (counts.isEmpty()) {
            (if (count == 1) "It has no files stored in Zotero." else "None of them has files stored in Zotero.") +
                    " Links and linked files stay where they are."
        } else {
            val here = if (all.onDevice.isEmpty()) "none" else "%,d".format(all.onDevice.size)
            "${if (count == 1) "Its" else "Their"} ${files(all.total)}: $here on this device. Links and linked files aren't counted."
        }

        for ((kind, c) in counts) {
            kinds.addView(CheckBox(themed).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                minHeight = (48 * resources.displayMetrics.density).toInt()
                text = DialogText.twoLines(themed, kind.label, describe(c))
                isChecked = true
                setOnCheckedChangeListener { _, on ->
                    if (on) ticked += kind else ticked -= kind
                    showButtons()
                }
            })
        }

        val shown = builder
            .setTitle(context.getString(R.string.manage_attachments))
            .setView(view)
            .setOnDismissListener { dialog = null }
            .show()
        dialog = shown
        DialogActions.leave(view, shown, "Close")
        showButtons()
    }

    /** The bulk downloads' progress ("Downloading 3 of 12"), or null when none are at work. */
    fun showDownloads(status: String?) {
        val visibility = if (status == null) View.GONE else View.VISIBLE
        downloadsHeader?.visibility = visibility
        downloadsRow?.visibility = visibility
        downloadsText?.text = status
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    // the buttons name how many files they act on, and do nothing without any
    private fun showButtons() {
        val c = chosen()
        downloadButton?.apply {
            text = "Download ${files(c.missing.size)}"
            isEnabled = c.missing.isNotEmpty()
        }
        removeButton?.apply {
            text = "Remove ${files(c.onDevice.size)}"
            isEnabled = c.onDevice.isNotEmpty()
        }
    }

    private fun describe(c: AttachmentCounts): String = when {
        c.missing.isEmpty() -> if (c.total == 1) "On this device" else "All %,d on this device".format(c.total)
        c.onDevice.isEmpty() -> if (c.total == 1) "Not on this device" else "None of %,d on this device".format(c.total)
        else -> "%,d of %,d on this device".format(c.onDevice.size, c.total)
    }
}
