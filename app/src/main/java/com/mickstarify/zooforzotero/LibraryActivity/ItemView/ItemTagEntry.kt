package com.mickstarify.zooforzotero.LibraryActivity.ItemView

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.mickstarify.zooforzotero.R

private const val ARG_TAG = "TAG"
private const val ARG_EDITABLE = "EDITABLE"

class ItemTagEntry : Fragment() {
    private var tagText: String? = null
    private var editable = false
    private var listener: OnTagEntryInteractionListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            tagText = it.getString(ARG_TAG)
            editable = it.getBoolean(ARG_EDITABLE)
        }
        onAttachToParentFragment(parentFragment)
    }

    fun onAttachToParentFragment(parentFragment: Fragment?) {
        if (parentFragment == null) {
            return
        }
        if (parentFragment is OnTagEntryInteractionListener) {
            listener = parentFragment
        } else {
            throw RuntimeException(parentFragment.toString() + " must implement OnTagEntryInteractionListener")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.item_tag_entry, container, false)
        val textView_tag = view.findViewById<TextView>(R.id.textView_tag)
        textView_tag.text = tagText

        val remove = view.findViewById<ImageButton>(R.id.imageButton_remove_tag)
        remove.visibility = if (editable) View.VISIBLE else View.GONE
        remove.contentDescription = "Remove tag $tagText"
        remove.setOnClickListener { tagText?.let { listener?.tagRemovePressed(it) } }

        return view
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    interface OnTagEntryInteractionListener {
        fun tagRemovePressed(tag: String)
    }

    companion object {
        @JvmStatic
        fun newInstance(tag: String, editable: Boolean) =
            ItemTagEntry().apply {
                arguments = Bundle().apply {
                    putString(ARG_TAG, tag)
                    putBoolean(ARG_EDITABLE, editable)
                }
            }
    }
}
