package com.mickstarify.zooforzotero.LibraryActivity

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import com.mickstarify.zooforzotero.R

/**
 * Lists every tag in the current library with its item count, filtered as you
 * type. Picking one calls [onTagPicked], which shows all items with that tag
 * across collections.
 */
class TagPickerDialog(
    val context: Context,
    private val tags: List<Pair<String, Int>>,
    private val onTagPicked: (String) -> Unit
) {
    fun show() {
        val dialogView: View = LayoutInflater.from(context).inflate(R.layout.dialog_tag_picker, null)
        val search = dialogView.findViewById<EditText>(R.id.editText_tag_search)
        val list = dialogView.findViewById<ListView>(R.id.listView_tags)
        val empty = dialogView.findViewById<TextView>(R.id.textView_no_tags)

        var shown = tags
        val adapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_1)
        fun refresh(query: String) {
            shown = tags.filter { it.first.contains(query.trim(), ignoreCase = true) }
            adapter.clear()
            adapter.addAll(shown.map { (tag, count) -> "$tag  ($count)" })
            empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        }
        list.adapter = adapter
        refresh("")

        val dialog = AlertDialog.Builder(context)
            .setTitle("Filter by tag")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refresh(s?.toString() ?: "")
            }
        })
        list.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            onTagPicked(shown[position].first)
        }

        dialog.show()
    }
}
