package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AlertDialog
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEdits

/** The dialogs of the list's bulk actions, which act on the selected items. */
class SelectionDialogs(private val context: Context) {

    private fun items(count: Int) = if (count == 1) "1 item" else "$count items"

    /**
     * Asks for a tag to add to the [count] selected items, suggesting the library's [tags]
     * (which may arrive later through the returned function).
     */
    fun addTag(count: Int, onAdd: (String) -> Unit): (List<String>) -> Unit {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_add_tag, null)
        val field = view.findViewById<AutoCompleteTextView>(R.id.editText_new_tag)
        val dialog = AlertDialog.Builder(context)
            .setTitle("Add a tag to ${items(count)}")
            .setView(view)
            .setPositiveButton("Add", null) // set below: a blank tag keeps the dialog open
            .setNegativeButton("Cancel", null)
            .create()
        val add = {
            val tag = ItemEdits.cleanTag(field.text.toString())
            if (tag == null) {
                field.error = if (field.text.isBlank()) "Type a tag" else "At most ${ItemEdits.MAX_TAG_LENGTH} characters"
            } else {
                onAdd(tag)
                dialog.dismiss()
            }
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { add(); true } else false
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { add() }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        field.requestFocus()
        return { tags ->
            field.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, tags))
        }
    }

    /**
     * Lists the [tags] on the [count] selected items, each with how many carry it; the one
     * tapped is removed from all of them.
     */
    fun removeTag(count: Int, tags: List<Pair<String, Int>>, onRemove: (String) -> Unit) {
        if (tags.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("No tags")
                .setMessage(if (count == 1) "The selected item has no tags." else "None of the $count items has tags.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = tags.map { (tag, n) -> if (n == count) tag else "$tag ($n of $count)" }
        AlertDialog.Builder(context)
            .setTitle("Remove a tag from ${items(count)}")
            .setItems(labels.toTypedArray()) { _, which -> onRemove(tags[which].first) }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
}
