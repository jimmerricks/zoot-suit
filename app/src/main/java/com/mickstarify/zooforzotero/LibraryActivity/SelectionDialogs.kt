package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentCounts
import com.mickstarify.zooforzotero.ZoteroStorage.Downloads.AttachmentKind

/** The dialogs of the list's bulk actions, which act on the selected items. */
class SelectionDialogs(private val context: Context) {

    private fun items(count: Int) = if (count == 1) "1 item" else "$count items"

    /**
     * Lists the library's [collections] as a tree (indented by depth); the one tapped gets
     * the [count] selected items.
     */
    fun addToCollection(count: Int, collections: List<Pair<Collection, Int>>, onChoose: (Collection) -> Unit) {
        if (collections.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("No collections")
                .setMessage("This library has no collections. Make them in Zotero.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = collections.map { (c, depth) -> "\u2003".repeat(depth) + c.name }
        AlertDialog.Builder(context)
            .setTitle("Add ${items(count)} to a collection")
            .setItems(labels.toTypedArray()) { _, which -> onChoose(collections[which].first) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Asks before taking the [count] selected items out of the collection [name]. */
    fun removeFromCollection(count: Int, name: String, onRemove: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Remove ${items(count)} from “$name”?")
            .setMessage("They stay in the library and in any other collections.")
            .setPositiveButton("Remove") { _, _ -> onRemove() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Which of the selection's attachments to download: each kind with how many of its
     * files aren't on this device yet ([counts]); [onDownload] gets those of the one tapped.
     */
    fun download(counts: Map<AttachmentKind, AttachmentCounts>, onDownload: (List<Item>) -> Unit) {
        if (counts.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("No attachments")
                .setMessage("The selected items have no files stored in Zotero (links aren't downloaded).")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val kinds = counts.keys.toList()
        val labels = kinds.map { kind ->
            val c = counts.getValue(kind)
            when {
                c.missing.isEmpty() -> "${kind.label}: all ${c.total} on this device"
                c.onDevice.isEmpty() -> "${kind.label}: ${c.missing.size} to download"
                else -> "${kind.label}: ${c.missing.size} to download, ${c.onDevice.size} on this device"
            }
        }
        AlertDialog.Builder(context)
            .setTitle("Download attachments")
            .setItems(labels.toTypedArray()) { _, which -> onDownload(counts.getValue(kinds[which]).missing) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Which of the selection's files to remove from this device: each kind with how many
     * of its files are here ([counts]); [onChoose] gets those of the one tapped.
     */
    fun removeAttachments(counts: Map<AttachmentKind, AttachmentCounts>, onChoose: (List<Item>) -> Unit) {
        val here = counts.filterValues { it.onDevice.isNotEmpty() }
        if (here.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("Nothing to remove")
                .setMessage("None of the selected items' files are on this device.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val kinds = here.keys.toList()
        val labels = kinds.map { kind ->
            val n = here.getValue(kind).onDevice.size
            "${kind.label}: $n on this device"
        }
        AlertDialog.Builder(context)
            .setTitle("Remove attachments from device")
            .setItems(labels.toTypedArray()) { _, which -> onChoose(here.getValue(kinds[which]).onDevice) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Asks before removing [files] from this device. If some have edits not uploaded yet
     * ([edited]), says so: those can be kept, or removed with their edits. [chosenFolder]:
     * the folder the user chose for attachments (AttachmentStorageManager.chosenFolderName),
     * whose files are theirs, so the dialog says they are deleted there.
     */
    fun confirmDelete(files: List<Item>, edited: List<Item>, chosenFolder: String?, onDelete: (List<Item>) -> Unit) {
        fun files(n: Int) = if (n == 1) "1 file" else "$n files"
        val dialog = AlertDialog.Builder(context)
            .setTitle("Remove ${files(files.size)} from this device?")
            .setNegativeButton("Cancel", null)
        val where = chosenFolder?.let { deletedFromFolder(it, files.size) + "\n\n" } ?: ""
        if (edited.isEmpty()) {
            dialog.setMessage(where + "They stay in Zotero and can be downloaded again.")
                .setPositiveButton("Remove") { _, _ -> onDelete(files) }
        } else {
            val keys = edited.map { it.itemKey }.toSet()
            val names = edited.take(5).joinToString("\n") { "• " + (it.data["filename"] ?: it.getTitle()) } +
                    if (edited.size > 5) "\n…" else ""
            dialog.setMessage(
                where + "${files(edited.size).replaceFirstChar { it.uppercase() }} ${if (edited.size == 1) "has" else "have"} " +
                        "edits that haven't been uploaded. Removing loses them.\n\n$names"
            )
                .setPositiveButton("Remove all") { _, _ -> onDelete(files) }
            if (edited.size < files.size) {
                dialog.setNeutralButton("Keep edited") { _, _ -> onDelete(files.filter { it.itemKey !in keys }) }
            }
        }
        dialog.show()
    }

    companion object {
        /** What removing [count] files does in the folder the user chose, named [folder]. */
        fun deletedFromFolder(folder: String, count: Int) =
            (if (count == 1) "It is" else "They are") + " deleted from \u201c$folder\u201d, the " +
                    "folder you chose in Settings, and gone for any other app that uses it."
    }
}
