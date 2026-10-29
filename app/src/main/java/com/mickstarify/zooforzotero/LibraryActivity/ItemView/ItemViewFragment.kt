package com.mickstarify.zooforzotero.LibraryActivity.ItemView

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import android.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEdits
import com.mickstarify.zooforzotero.LibraryActivity.Notes.EditNoteDialog
import com.mickstarify.zooforzotero.LibraryActivity.Notes.NoteInteractionListener
import com.mickstarify.zooforzotero.LibraryActivity.Notes.onEditNoteChangeListener
import com.mickstarify.zooforzotero.LibraryActivity.ViewModels.LibraryListViewModel
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.ZoteroAPI.Model.Note
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Creator
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Item

/**
 * The item dialog: attachments, fields, notes and tags of the clicked item, styled like
 * the app's AlertDialogs (filter, sort). Activities containing this fragment MUST
 * implement [ItemViewFragment.OnItemFragmentInteractionListener].
 */
class ItemViewFragment : DialogFragment(),
    NoteInteractionListener,
    ItemTagEntry.OnTagEntryInteractionListener {

    lateinit var libraryViewModel: LibraryListViewModel

    private lateinit var attachments: List<Item>
    private lateinit var notes: List<Note>
    private var listener: OnItemFragmentInteractionListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the theme android.app.AlertDialog uses, so card, title and buttons match it
        val theme = TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.alertDialogTheme, theme, true)
        setStyle(STYLE_NO_TITLE, theme.resourceId)
    }

    override fun editNote(note: Note) {
        EditNoteDialog()
            .show(context, note.note, object :
                onEditNoteChangeListener {
                override fun onCancel() {
                }

                override fun onSubmit(noteText: String) {
                    note.note = noteText
                    listener?.onNoteEdit(note)
                }
            })
    }

    override fun deleteNote(note: Note) {
        listener?.onNoteDelete(note)
    }


    override fun onStart() {
        super.onStart()
        // a long abstract would widen the dialog to the screen's edges; keep it as wide as
        // the other dialogs: Material's 560dp, or nearly the width of a phone
        val metrics = resources.displayMetrics
        val width = minOf((560 * metrics.density).toInt(), (metrics.widthPixels * 0.95).toInt())
        dialog?.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_item_main, container, false)
        return view
    }


    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        libraryViewModel =
            ViewModelProvider(requireActivity()).get(LibraryListViewModel::class.java)

        if (libraryViewModel.getOnItemClicked().value == null) {
            Log.e("zotero", "error item in viewmodel is null!")
            dismiss()
        }

        requireView().findViewById<Button>(R.id.button_item_close).setOnClickListener {
            dismiss()
        }
        requireView().findViewById<View>(R.id.row_add_tag).setOnClickListener {
            libraryViewModel.getOnItemClicked().value?.let { showAddTagDialog(it) }
        }
        requireView().findViewById<View>(R.id.row_add_note).setOnClickListener {
            libraryViewModel.getOnItemClicked().value?.let { showAddNoteDialog(it) }
        }

        libraryViewModel.getOnItemClicked().observe(viewLifecycleOwner) { render(it) }
        libraryViewModel.getOnItemChanged().observe(viewLifecycleOwner) { item ->
            if (item === libraryViewModel.getOnItemClicked().value) render(item)
        }
    }

    /** Draws [item]; again whenever it changes, so everything below the headers is redone. */
    private fun render(item: Item) {
        attachments = item.attachments
        notes = item.notes
        clearEntries()
        requireView().findViewById<TextView>(R.id.textView_item_toolbar_title).text = item.getTitle()
        val type = ItemFieldLabel.of(item.data["itemType"] ?: "Unknown")
        requireView().findViewById<TextView>(R.id.textView_item_subtitle).text =
            if (listener?.hasPendingEdits(item) == true) "$type · edits waiting to sync" else type
        addTextEntry("Title", item.getTitle())
        this.addCreators(item.getSortedCreators())
        for ((key, value) in item.data) {
            if (value != "" && key != "itemType" && key != "title") {
                addTextEntry(ItemFieldLabel.of(key), value)
            }
        }
        this.addAttachments(attachments)
        this.populateNotes(notes)
        val editable = listener?.canEditItems() == true
        this.populateTags(item.tags.map { it.tag }.sortedWith(String.CASE_INSENSITIVE_ORDER), editable)
        showSection(R.id.item_fragment_scrollview_ll_attachments, attachments.isNotEmpty())
        // with editing, notes and tags show even when empty: their "Add note"/"Add tag" rows
        showSection(R.id.item_fragment_scrollview_ll_notes, editable || notes.isNotEmpty())
        showSection(R.id.row_add_note, editable)
        showSection(R.id.item_fragment_scrollview_ll_tags, editable || item.tags.isNotEmpty())
        showSection(R.id.row_add_tag, editable)
    }

    /** Removes every row below the section headers: field views and entry fragments. */
    private fun clearEntries() {
        childFragmentManager.executePendingTransactions()
        val fmt = childFragmentManager.beginTransaction()
        childFragmentManager.fragments.forEach { fmt.remove(it) }
        fmt.commitNow()
        val fields = requireView().findViewById<LinearLayout>(R.id.item_fragment_scrollview_ll_layout)
        fields.removeViews(1, fields.childCount - 1)
    }

    private fun showSection(id: Int, show: Boolean) {
        requireView().findViewById<View>(id).visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * Asks for a note to add to [item]. Added on this device at once; it goes to Zotero
     * with the next sync, like a tag.
     */
    private fun showAddNoteDialog(item: Item) {
        EditNoteDialog().show(context, "", object : onEditNoteChangeListener {
            override fun onCancel() {}

            override fun onSubmit(noteText: String) {
                listener?.onNoteAdd(item, noteText)
            }
        })
    }

    /**
     * Asks for a tag to add to [item], suggesting the library's other tags as it is typed.
     * Added on this device at once; it goes to Zotero with the next sync.
     */
    private fun showAddTagDialog(item: Item) {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_tag, null)
        val field = view.findViewById<AutoCompleteTextView>(R.id.editText_new_tag)
        val itemTags = item.tags.map { it.tag }.toSet()
        // the suggestions arrive while the dialog is open
        listener?.libraryTags { tags ->
            if (!isAdded) return@libraryTags
            val suggestions = tags.filter { it !in itemTags }
            field.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, suggestions))
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Add tag")
            .setView(view)
            .setPositiveButton("Add", null) // set below: a blank tag keeps the dialog open
            .setNegativeButton("Cancel", null)
            .create()
        val add = {
            val tag = ItemEdits.cleanTag(field.text.toString())
            if (tag == null) {
                field.error = if (field.text.isBlank()) "Type a tag" else "At most ${ItemEdits.MAX_TAG_LENGTH} characters"
            } else {
                listener?.onTagAdd(item, tag)
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
    }

    private fun populateTags(tags: List<String>, editable: Boolean) {
        val fmt = this.childFragmentManager.beginTransaction()
        for (tag in tags) {
            fmt.add(
                R.id.item_fragment_scrollview_ll_tags,
                ItemTagEntry.newInstance(tag, editable)
            )
        }
        fmt.commit()
    }

    private fun populateNotes(notes: List<Note>) {
        if (notes.isEmpty()) {
            return
        }
        val fmt = this.childFragmentManager.beginTransaction()
        for (note in notes) {
            fmt.add(
                R.id.item_fragment_scrollview_ll_notes,
                ItemNoteEntry.newInstance(note.key)
            )
        }
        fmt.commit()
    }

    private fun addAttachments(attachments: List<Item>) {
        val fmt = this.childFragmentManager.beginTransaction()
        for (attachment in attachments) {
            Log.d("zotero", "adding ${attachment.getTitle()}")
            fmt.add(
                R.id.item_fragment_scrollview_ll_attachments,
                ItemAttachmentEntry.newInstance(attachment.itemKey)
            )
        }
        fmt.commit()
    }

    private fun addTextEntry(label: String, content: String) {
        val layout =
            requireView().findViewById<LinearLayout>(R.id.item_fragment_scrollview_ll_layout)

        val inflater = LayoutInflater.from(requireContext())
        val view = inflater.inflate(R.layout.fragment_item_text_entry, layout)
        val viewGroup = view as ViewGroup
        val textLayout = viewGroup.getChildAt(viewGroup.childCount - 1)

        val textLabel = textLayout.findViewById<TextView>(R.id.textView_label)
        textLabel.text = label

        val textViewInfo = textLayout.findViewById<TextView>(R.id.textView_item_info)
        textViewInfo.setText(content)
    }

    /** Each creator as a field, like Zotero: its role ("Author", "Editor") and "Last, First". */
    private fun addCreators(creators: List<Creator>) {
        for (creator in creators) {
            val name = ItemFieldLabel.creatorName(creator.firstName, creator.lastName)
            if (name.isNotEmpty()) addTextEntry(ItemFieldLabel.of(creator.creatorType), name)
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnItemFragmentInteractionListener) {
            listener = context
        } else {
            throw RuntimeException(context.toString() + " must implement OnItemFragmentInteractionListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     *
     *
     * See the Android Training lesson
     * [Communicating with Other Fragments](http://developer.android.com/training/basics/fragments/communicating.html)
     * for more information.
     */
    interface OnItemFragmentInteractionListener {
        fun onListFragmentInteraction(item: Item?)
        fun onNoteAdd(item: Item, text: String)
        fun onNoteEdit(note: Note)
        fun onNoteDelete(note: Note)
        fun onTagAdd(item: Item, tag: String)
        fun onTagRemove(item: Item, tag: String)

        /** Whether notes can be added and tags added and removed here (My Library, not a group). */
        fun canEditItems(): Boolean

        /** The open library's tags, for suggestions; [onResult] runs on the main thread. */
        fun libraryTags(onResult: (List<String>) -> Unit)
        fun hasPendingEdits(item: Item): Boolean
    }

    companion object {
        @JvmStatic
        fun newInstance() =
            ItemViewFragment()
    }

    override fun tagRemovePressed(tag: String) {
        libraryViewModel.getOnItemClicked().value?.let { listener?.onTagRemove(it, tag) }
    }
}
