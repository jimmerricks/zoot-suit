package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item
import com.mickstarify.zooforzotero.common.ConfirmDialog
import com.mickstarify.zooforzotero.common.DialogText

/** The dialogs of the list's bulk actions, which act on the selected items. */
class SelectionDialogs(private val context: Context) {

    private fun items(count: Int) = if (count == 1) "1 item" else "$count items"

    /**
     * Lets the user choose one of the library's [collections] (a tree, each with its
     * depth), then add the [count] selected items to it (AddToCollectionDialog).
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
        AddToCollectionDialog(context, count, collections, onChoose).show()
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
     * Asks before removing [files] from this device. If some have edits not uploaded yet
     * ([edited]), says so: those can be kept, or removed with their edits. The answers are
     * full-width buttons (ConfirmDialog): three in the bar stacked on a phone. [chosenFolder]:
     * the folder the user chose for attachments (AttachmentStorageManager.chosenFolderName),
     * whose files are theirs, so the dialog says they are deleted there.
     */
    fun confirmDelete(files: List<Item>, edited: List<Item>, chosenFolder: String?, onDelete: (List<Item>) -> Unit) {
        val title = "Remove ${DialogText.files(files.size)} from this device?"
        val where = chosenFolder?.let { deletedFromFolder(it, files.size) + "\n\n" } ?: ""
        if (edited.isEmpty()) {
            ConfirmDialog.show(
                context, title, where + "They stay in Zotero and can be downloaded again.",
                main = "Remove ${DialogText.files(files.size)}" to { onDelete(files) },
            )
            return
        }
        val keys = edited.map { it.itemKey }.toSet()
        val names = edited.take(5).joinToString("\n") { "• " + (it.data["filename"] ?: it.getTitle()) } +
                if (edited.size > 5) "\n…" else ""
        val message = where + "${DialogText.files(edited.size).replaceFirstChar { it.uppercase() }} ${if (edited.size == 1) "has" else "have"} " +
                "edits that haven't been uploaded. Removing loses them.\n\n$names"
        val unedited = files.filter { it.itemKey !in keys }
        // keeping the edited ones is the safe answer, so it's the main one when there is a choice
        if (unedited.isEmpty()) {
            ConfirmDialog.show(context, title, message, main = "Remove ${DialogText.files(files.size)}" to { onDelete(files) })
        } else {
            ConfirmDialog.show(
                context, title, message,
                main = "Remove ${"%,d".format(unedited.size)} unedited ${if (unedited.size == 1) "file" else "files"}" to { onDelete(unedited) },
                other = "Remove all ${DialogText.files(files.size)}" to { onDelete(files) },
            )
        }
    }

    companion object {
        /** What removing [count] files does in the folder the user chose, named [folder]. */
        fun deletedFromFolder(folder: String, count: Int) =
            (if (count == 1) "It is" else "They are") + " deleted from \u201c$folder\u201d, the " +
                    "folder you chose in Settings, and gone for any other app that uses it."
    }
}
