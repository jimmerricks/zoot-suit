package com.mickstarify.zooforzotero.LibraryActivity.Notes

import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note

interface NoteInteractionListener {
    fun deleteNote(note: Note)
    /** Opens [note] for editing (editNoteAsText), and saves it if changed. */
    fun editNote(note: Note)
}