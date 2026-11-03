package com.mickstarify.zooforzotero.LibraryActivity.Notes

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.mickstarify.zooforzotero.R

/**
 * Adding a note, or editing one ([noteText] not empty): laid out like Manage attachments,
 * the field, then Save note as a full-width button; Cancel alone in the bar. The keyboard
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
            .setNegativeButton("Cancel") { _, _ -> onEditNoteChangeListener.onCancel() }
            .create()
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

interface onEditNoteChangeListener {
    fun onCancel()
    fun onSubmit(noteText: String)
}
