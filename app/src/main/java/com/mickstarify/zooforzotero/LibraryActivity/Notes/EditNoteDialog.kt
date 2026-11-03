package com.mickstarify.zooforzotero.LibraryActivity.Notes

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.common.DialogActions
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEdits

/**
 * Adding a note, or editing one ([noteText] not empty): laid out like Manage attachments,
 * the field, then Save note and Cancel (DialogActions). The keyboard
 * comes up with it.
 */
class EditNoteDialog {

    fun show(
        context: Context?,
        noteText: String,
        onEditNoteChangeListener: onEditNoteChangeListener
    ) {
        if (context == null) {
            Log.e("zotero", "got null context on create Note")
            return
        }
        val builder = AlertDialog.Builder(context)
        val dialogView = LayoutInflater.from(builder.context).inflate(R.layout.dialog_add_note, null)
        val submitButton = dialogView.findViewById<Button>(R.id.buttonSubmit)
        val editTextNote = dialogView.findViewById<EditText>(R.id.edit_note)
        editTextNote.setText(noteText)
        editTextNote.setSelection(noteText.length)

        val dialog = builder
            .setTitle(if (noteText.isEmpty()) "Add note" else "Edit note")
            .setView(dialogView)
            .create()
        DialogActions.leave(dialogView, dialog, "Cancel") { onEditNoteChangeListener.onCancel() }
        submitButton.setOnClickListener {
            onEditNoteChangeListener.onSubmit(editTextNote.text.toString())
            dialog.dismiss()
        }
        // a tap beside the dialog would lose what was typed
        dialog.setCanceledOnTouchOutside(false)
        // the field has focus, so the keyboard comes up with the dialog; it goes with it
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        editTextNote.requestFocus()
        dialog.show()
    }
}

/**
 * Edits [note] as plain text and gives [onSave] a copy with the new text ([note] itself is
 * left as it is until the change is saved). A note with formatting the app can't show as
 * text (headings, bold, links, citations, images) isn't opened for editing: saving it as
 * text would strip that from it everywhere. It says so instead.
 */
fun editNoteAsText(context: Context, note: Note, onSave: (Note) -> Unit) {
    val text = ItemEdits.noteText(note.note)
    if (text == null) {
        AlertDialog.Builder(context)
            .setTitle("Edit in Zotero")
            .setMessage("This note has formatting (such as headings, bold, links, citations or " +
                    "images) that editing it here would remove. Edit it in Zotero instead.")
            .setPositiveButton("OK", null)
            .show()
        return
    }
    EditNoteDialog().show(context, text, object : onEditNoteChangeListener {
        override fun onCancel() {}

        override fun onSubmit(noteText: String) {
            if (noteText.trim() == text) return // unchanged
            val html = ItemEdits.editedNoteHtml(note.note, noteText)
            if (html == null) {
                AlertDialog.Builder(context)
                    .setTitle("The note is empty")
                    .setMessage("An empty note isn't saved. To remove the note, delete it.")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
            onSave(Note().apply {
                parent = note.parent
                key = note.key
                this.note = html
                version = note.version
                tags = note.tags
            })
        }
    })
}

interface onEditNoteChangeListener {
    fun onCancel()
    fun onSubmit(noteText: String)
}
