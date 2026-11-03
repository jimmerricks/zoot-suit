package com.mickstarify.zooforzotero.LibraryActivity.Notes

import android.content.Context
import android.text.Html
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.common.DialogActions
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note

/**
 * A note, its text selectable; laid out like Manage attachments, Edit note is a full-width
 * button under it, Close beside or under it (DialogActions).
 */
class NoteView(val context: Context, val note: Note, val listener: NoteInteractionListener) {

    fun show() {
        val builder = AlertDialog.Builder(context)
        val view = LayoutInflater.from(builder.context).inflate(R.layout.dialog_note_view, null)
        view.findViewById<TextView>(R.id.textView_note).text = Html.fromHtml(note.note, Html.FROM_HTML_MODE_LEGACY).trimEnd()
        val dialog = builder
            .setTitle("Note")
            .setView(view)
            .show()
        DialogActions.leave(view, dialog, "Close")
        view.findViewById<Button>(R.id.button_edit_note).setOnClickListener {
            dialog.dismiss()
            showNoteEditingDialog()
        }
    }

    fun showNoteEditingDialog() {
        listener.editNote(note)
    }
}
