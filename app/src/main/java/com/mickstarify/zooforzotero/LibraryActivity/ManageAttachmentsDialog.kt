package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.common.themeColor
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentCounts
import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentKind

/**
 * "Manage attachments" for the items selected in the list: their stored files by kind
 * (PDFs, HTML snapshots, others), each with how many are on this device, ticked to be
 * acted on; Download fetches the ticked kinds' missing files in the background, Remove
 * takes their copies off this device (after asking). While bulk downloads are at work it
 * says how far they are, with a button to stop them. Counts and lists only: as cheap for
 * the whole library as for one item.
 *
 * @param count the number of items selected
 * @param counts the selection's stored files by kind (AttachmentCounts.of); kinds without any are left out
 * @param onDownload the ticked kinds' files not on this device
 * @param onRemove the ticked kinds' files on this device
 * @param onStop stops the bulk downloads
 */
class ManageAttachmentsDialog(
    private val context: Context,
    private val count: Int,
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

    private fun chosen(): AttachmentCounts =
        counts.filterKeys { it in ticked }.values.fold(AttachmentCounts(emptyList(), emptyList())) { a, b -> a + b }

    fun show() {
        val builder = AlertDialog.Builder(context)
        // the dialog's theme, for the rows' colours
        val themed = builder.context
        val view = LayoutInflater.from(themed).inflate(R.layout.dialog_manage_attachments, null)
        val hint = view.findViewById<TextView>(R.id.textView_attachments_hint)
        val kinds = view.findViewById<LinearLayout>(R.id.layout_attachment_kinds)
        downloadsHeader = view.findViewById(R.id.textView_downloads_header)
        downloadsRow = view.findViewById(R.id.layout_downloads)
        downloadsText = view.findViewById(R.id.textView_downloads)
        view.findViewById<Button>(R.id.button_stop_downloads).setOnClickListener { onStop() }

        val all = counts.values.fold(AttachmentCounts(emptyList(), emptyList())) { a, b -> a + b }
        val items = if (count == 1) "the selected item" else "the %,d selected items".format(count)
        hint.text = if (counts.isEmpty()) {
            (if (count == 1) "The selected item has no files stored in Zotero."
            else "None of the %,d selected items has files stored in Zotero.".format(count)) +
                    " Links and linked files stay where they are."
        } else {
            val here = if (all.onDevice.isEmpty()) "none" else "%,d".format(all.onDevice.size)
            "The files of $items: %,d, $here on this device. Links and linked files aren't counted.".format(all.total)
        }

        val secondary = themed.themeColor(android.R.attr.textColorSecondary)
        for ((kind, c) in counts) {
            kinds.addView(CheckBox(themed).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                minHeight = (48 * resources.displayMetrics.density).toInt()
                text = SpannableStringBuilder(kind.label).apply {
                    val start = length
                    append("\n").append(describe(c))
                    setSpan(ForegroundColorSpan(secondary), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                isChecked = true
                setOnCheckedChangeListener { _, on ->
                    if (on) ticked += kind else ticked -= kind
                    showButtons()
                }
            })
        }

        dialog = builder
            .setTitle(context.getString(R.string.manage_attachments))
            .setView(view)
            .setPositiveButton("Download") { _, _ -> chosen().missing.takeIf { it.isNotEmpty() }?.let(onDownload) }
            .setNeutralButton("Remove") { _, _ -> chosen().onDevice.takeIf { it.isNotEmpty() }?.let(onRemove) }
            .setNegativeButton("Close", null)
            .setOnDismissListener { dialog = null }
            .show()
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
        val d = dialog ?: return
        val c = chosen()
        d.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            text = if (c.missing.isEmpty()) "Download" else "Download %,d".format(c.missing.size)
            isEnabled = c.missing.isNotEmpty()
        }
        d.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
            text = if (c.onDevice.isEmpty()) "Remove" else "Remove %,d".format(c.onDevice.size)
            isEnabled = c.onDevice.isNotEmpty()
        }
    }

    private fun describe(c: AttachmentCounts): String = when {
        c.missing.isEmpty() -> if (c.total == 1) "On this device" else "All %,d on this device".format(c.total)
        c.onDevice.isEmpty() -> if (c.total == 1) "Not on this device" else "None of %,d on this device".format(c.total)
        else -> "%,d of %,d on this device".format(c.onDevice.size, c.total)
    }
}
