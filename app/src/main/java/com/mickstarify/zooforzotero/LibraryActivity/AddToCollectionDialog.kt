package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.common.DialogActions
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.common.DialogText

/**
 * "Add to collection" for the items selected in the list, laid out like Manage attachments:
 * what it applies to, how it works, the library's collections as a tree (indented by
 * depth) of one-choice rows, and a full-width button that names the collection chosen.
 * Tapping a collection only chooses it; nothing changes until that button. Cancel (or
 * Back) changes nothing.
 *
 * @param count the number of items selected
 * @param collections the library's collections in tree order, each with its depth
 * @param onChoose adds the selected items to the collection
 */
class AddToCollectionDialog(
    private val context: Context,
    private val count: Int,
    private val collections: List<Pair<Collection, Int>>,
    private val onChoose: (Collection) -> Unit,
) {
    private var chosen = -1

    fun show() {
        val builder = AlertDialog.Builder(context)
        val themed = builder.context
        val inflater = LayoutInflater.from(themed)
        val view = inflater.inflate(R.layout.dialog_add_to_collection, null)
        val add = view.findViewById<Button>(R.id.button_add_to_collection)
        val list = view.findViewById<RecyclerView>(R.id.recyclerView_collections)
        view.findViewById<TextView>(R.id.textView_add_to_collection_scope).text =
            DialogText.appliesTo(count, listed = false)
        view.findViewById<TextView>(R.id.textView_add_to_collection_hint).text =
            (if (count == 1) "Choose a collection to add it to. It stays in any others."
            else "Choose a collection to add them to. They stay in any others.")
        list.layoutManager = LinearLayoutManager(themed)
        // a tap redraws the rows; no fading in and out for that
        list.itemAnimator = null

        val density = themed.resources.displayMetrics.density
        val start = (4 * density).toInt()
        val indent = (24 * density).toInt()

        fun render() {
            add.isEnabled = chosen >= 0
            add.text = collections.getOrNull(chosen)?.let { "Add to “${it.first.name}”" }
                ?: context.getString(R.string.add_to_collection)
        }

        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = collections.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = inflater.inflate(R.layout.dialog_collection_choice, parent, false)
                return object : RecyclerView.ViewHolder(row) {}.apply {
                    itemView.setOnClickListener {
                        val position = bindingAdapterPosition
                        if (position == RecyclerView.NO_POSITION || position == chosen) return@setOnClickListener
                        val before = chosen
                        chosen = position
                        if (before >= 0) bindingAdapter?.notifyItemChanged(before)
                        bindingAdapter?.notifyItemChanged(position)
                        render()
                    }
                }
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val row = holder.itemView as CheckedTextView
                val (collection, depth) = collections[position]
                row.text = collection.name
                row.isChecked = position == chosen
                row.setPaddingRelative(start + indent * depth, row.paddingTop, row.paddingEnd, row.paddingBottom)
                row.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    if (position == chosen) R.drawable.baseline_radio_button_checked_24
                    else R.drawable.baseline_radio_button_unchecked_24, 0, 0, 0
                )
            }
        }

        val dialog = builder
            .setTitle(context.getString(R.string.add_to_collection))
            .setView(view)
            .create()
        DialogActions.leave(view, dialog, "Cancel")
        add.setOnClickListener {
            val collection = collections.getOrNull(chosen) ?: return@setOnClickListener
            dialog.dismiss()
            onChoose(collection.first)
        }
        render()
        dialog.show()
    }
}
