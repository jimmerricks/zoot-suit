package com.mickstarify.zooforzotero.LibraryActivity.Notes

import android.content.Context
import android.text.Html
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note

/**
 * A note, its text selectable; laid out like Manage attachments, Edit note is a full-width
 * button under it and Close is alone in the bar.
 */
class NoteView(val context: Context, val note: Note, val listener: NoteInteractionListener) {

    fun show() {
        val builder = AlertDialog.Builder(context)
        val view = LayoutInflater.from(builder.context).inflate(R.layout.dialog_note_view, null)
        view.findViewById<TextView>(R.id.textView_note).text = Html.fromHtml(note.note).trimEnd()
        val dialog = builder
            .setTitle("Note")
            .setView(view)
            .setNegativeButton("Close", null)
            .show()
        view.findViewById<Button>(R.id.button_edit_note).setOnClickListener {
            dialog.dismiss()
            showNoteEditingDialog()
        }
    }

    fun showNoteEditingDialog() {
        EditNoteDialog()
            .show(context, note.note, object :
                onEditNoteChangeListener {
                override fun onCancel() {
                }

                override fun onSubmit(noteText: String) {
                    note.note = noteText
                    listener.editNote(note)

                }

            })
    }
}
