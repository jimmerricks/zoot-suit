package com.mickstarify.zooforzotero.LibraryActivity.ItemView

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.fragment.app.DialogFragment
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
    onShareItemListener,
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

    private fun showShareItemDialog() {
        val item = libraryViewModel.getOnItemClicked().value
        if (item == null) {
            Toast.makeText(requireContext(), "The item is still loading.", Toast.LENGTH_SHORT).show()
        } else {
            ShareItemDialog(item).show(context, this)
        }


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


    private fun showCreateNoteDialog() {
        EditNoteDialog()
            .show(context, "", object :
                onEditNoteChangeListener {
                override fun onCancel() {
                }

                override fun onSubmit(noteText: String) {
                    Log.d("zotero", "got note $noteText")
                    val item = libraryViewModel.getOnItemClicked().value
                    if (item == null) {
                        Toast.makeText(
                            requireContext(),
                            "Note not saved: the item was closed. Open it and try again.",
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }
                    val note = Note(noteText, item.itemKey)
                    listener?.onNoteCreate(note)
                }
            })
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

        val textViewTitle = requireView().findViewById<TextView>(R.id.textView_item_toolbar_title)
        val textViewSubtitle = requireView().findViewById<TextView>(R.id.textView_item_subtitle)
        requireView().findViewById<Button>(R.id.button_item_add_note).setOnClickListener {
            showCreateNoteDialog()
        }
        requireView().findViewById<Button>(R.id.button_item_share).setOnClickListener {
            showShareItemDialog()
        }
        requireView().findViewById<Button>(R.id.button_item_close).setOnClickListener {
            dismiss()
        }

        libraryViewModel.getOnItemClicked().observe(viewLifecycleOwner) { item ->
            attachments = item.attachments
            notes = item.notes

            // the item is emitted again when it changes (a note added, say): draw it anew
            clearEntries()
            textViewTitle.text = item.getTitle()
            textViewSubtitle.text = ItemFieldLabel.of(item.data["itemType"] ?: "Unknown")
            addTextEntry("Title", item.getTitle())
            this.addCreators(item.getSortedCreators())
            for ((key, value) in item.data) {
                if (value != "" && key != "itemType" && key != "title") {
                    addTextEntry(ItemFieldLabel.of(key), value)
                }
            }
            this.addAttachments(attachments)
            this.populateNotes(notes)
            this.populateTags(item.tags.map { it.tag })
            showSection(R.id.item_fragment_scrollview_ll_attachments, attachments.isNotEmpty())
            showSection(R.id.item_fragment_scrollview_ll_notes, notes.isNotEmpty())
            showSection(R.id.item_fragment_scrollview_ll_tags, item.tags.isNotEmpty())
        }
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

    private fun populateTags(tags: List<String>) {
        val fmt = this.childFragmentManager.beginTransaction()
        for (tag in tags) {
            fmt.add(
                R.id.item_fragment_scrollview_ll_tags,
                ItemTagEntry.newInstance(tag)
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
        fun onNoteCreate(note: Note)
        fun onNoteEdit(note: Note)
        fun onNoteDelete(note: Note)
        fun shareText(shareText: String)
        fun onTagOpenListener(tag: String)
    }

    companion object {
        @JvmStatic
        fun newInstance() =
            ItemViewFragment()
    }

    override fun shareItem(shareText: String) {
        listener?.shareText(shareText)
    }

    override fun tagPressed(tag: String) {
        listener?.onTagOpenListener(tag)
        this.dismiss()
    }
}
