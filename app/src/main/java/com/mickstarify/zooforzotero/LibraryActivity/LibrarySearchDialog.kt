package com.mickstarify.zooforzotero.LibraryActivity

import android.app.AlertDialog
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.R

/**
 * Library search, laid out like [LibraryFilterDialog] and [LibrarySortDialog]: the query,
 * then the fields to search in. Like the filter, a search narrows whatever list is shown
 * until it is cleared; unlike it, nothing runs until Search (or the keyboard's search key),
 * as each search scans the library. The chosen fields are kept for next time. With a
 * search on, it opens with that query and offers Clear, which ends the search.
 *
 * @param current the open library's search, or ""
 * @param onSearch sets the search; "" ends it
 */
class LibrarySearchDialog(
    private val context: Context,
    private val current: String,
    private val onSearch: (String) -> Unit,
) {
    private val preferences = PreferenceManager(context)

    fun show() {
        val view: View = LayoutInflater.from(context).inflate(R.layout.dialog_library_search, null)
        val query = view.findViewById<EditText>(R.id.editText_search_query)
        val fieldList = view.findViewById<LinearLayout>(R.id.layout_search_fields)

        val chosen = preferences.getSearchFields()
        val boxes = LibrarySearch.Field.entries.associateWith { field ->
            CheckBox(context).apply {
                text = field.label
                isChecked = field in chosen
                minHeight = (36 * context.resources.displayMetrics.density).toInt()
                fieldList.addView(this, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        }
        query.setText(current)
        query.setSelection(current.length)

        val builder = AlertDialog.Builder(context)
            .setTitle("Search")
            .setView(view)
            .setPositiveButton("Search", null) // set below: a blank query keeps the dialog open
            .setNegativeButton("Cancel", null)
        if (current.isNotEmpty()) builder.setNeutralButton("Clear") { _, _ -> onSearch("") }
        val dialog = builder.create()

        fun selectedFields() = boxes.filterValues { it.isChecked }.keys
        fun search() {
            val text = query.text.toString()
            if (text.isBlank() || selectedFields().isEmpty()) return
            preferences.setSearchFields(selectedFields())
            dialog.dismiss()
            onSearch(text.trim())
        }
        fun updateSearchButton() {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = selectedFields().isNotEmpty()
        }

        boxes.values.forEach { box -> box.setOnCheckedChangeListener { _, _ -> updateSearchButton() } }
        // the keyboard's search key, or Enter on a hardware keyboard
        query.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId != EditorInfo.IME_ACTION_SEARCH && !enter) return@setOnEditorActionListener false
            if (event == null || event.action == KeyEvent.ACTION_DOWN) search()
            true
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { search() }
            updateSearchButton()
        }
        // the query has focus, so the keyboard comes up with the dialog
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        query.requestFocus()
        dialog.show()
    }
}
