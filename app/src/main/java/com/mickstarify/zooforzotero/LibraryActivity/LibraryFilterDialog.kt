package com.mickstarify.zooforzotero.LibraryActivity

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import com.mickstarify.zooforzotero.R

/**
 * Filter for the library list: device conditions and tags, all ticked conditions must
 * hold. Every tick is applied at once through [apply], so the list behind the dialog and
 * the counts here follow each change. Cancel (or Back) restores the filter the dialog
 * opened with; Clear unticks everything; Done keeps the current selection.
 *
 * @param apply sets a filter on the list and returns what it now shows
 */
class LibraryFilterDialog(
    private val context: Context,
    private val initial: LibraryFilter,
    private val apply: (LibraryFilter) -> Preview,
) {
    /** What the list shows under a filter: item count and its tags with their counts. */
    data class Preview(val itemCount: Int, val tagCounts: List<Pair<String, Int>>)

    private var filter = initial
    private var preview = apply(initial)
    private var query = ""
    private var shownTags: List<String> = emptyList()

    fun show() {
        val view: View = LayoutInflater.from(context).inflate(R.layout.dialog_library_filter, null)
        val count = view.findViewById<TextView>(R.id.textView_filter_count)
        val hasPdf = view.findViewById<CheckBox>(R.id.checkBox_filter_has_pdf)
        val hasNotes = view.findViewById<CheckBox>(R.id.checkBox_filter_has_notes)
        val onDevice = view.findViewById<CheckBox>(R.id.checkBox_filter_on_device)
        val edited = view.findViewById<CheckBox>(R.id.checkBox_filter_edited)
        val search = view.findViewById<EditText>(R.id.editText_tag_search)
        val list = view.findViewById<ListView>(R.id.listView_tags)
        val noTags = view.findViewById<TextView>(R.id.textView_no_tags)
        val adapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_multiple_choice)
        list.adapter = adapter

        fun render() {
            count.text = when (preview.itemCount) {
                1 -> "1 item matches"
                else -> "${preview.itemCount} items match"
            }
            hasPdf.isChecked = filter.hasPdf
            hasNotes.isChecked = filter.hasNotes
            onDevice.isChecked = filter.onDevice
            edited.isChecked = filter.editedHere
            // tags in what the list shows now, plus the selected ones; selected first
            val counts = preview.tagCounts.toMap()
            val all = (counts.keys + filter.tags).filter { it.contains(query.trim(), ignoreCase = true) }
            shownTags = all.sortedWith(
                compareBy<String> { it !in filter.tags }.thenBy(String.CASE_INSENSITIVE_ORDER) { it }
            )
            adapter.clear()
            adapter.addAll(shownTags.map { "$it  (${counts[it] ?: 0})" })
            shownTags.forEachIndexed { i, tag -> list.setItemChecked(i, tag in filter.tags) }
            noTags.visibility = if (shownTags.isEmpty()) View.VISIBLE else View.GONE
        }

        fun update(newFilter: LibraryFilter) {
            filter = newFilter
            preview = apply(newFilter)
            render()
        }

        hasPdf.setOnClickListener { update(filter.copy(hasPdf = hasPdf.isChecked)) }
        hasNotes.setOnClickListener { update(filter.copy(hasNotes = hasNotes.isChecked)) }
        onDevice.setOnClickListener { update(filter.copy(onDevice = onDevice.isChecked)) }
        edited.setOnClickListener { update(filter.copy(editedHere = edited.isChecked)) }
        list.setOnItemClickListener { _, _, position, _ ->
            val tag = shownTags[position]
            update(filter.copy(tags = if (tag in filter.tags) filter.tags - tag else filter.tags + tag))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                render()
            }
        })

        val dialog = AlertDialog.Builder(context)
            .setTitle("Filter")
            .setView(view)
            .setPositiveButton("Done", null)
            .setNegativeButton("Cancel") { _, _ -> apply(initial) }
            .setNeutralButton("Clear", null) // set below so it doesn't close the dialog
            .setOnCancelListener { apply(initial) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                search.setText("")
                update(LibraryFilter())
            }
        }
        // no keyboard until the tag search is tapped; it would cover half the dialog
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        render()
        dialog.show()
    }
}
