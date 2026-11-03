package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.R

/**
 * Library search, laid out like [LibraryFilterDialog] and [LibrarySortDialog]: the query,
 * then the fields to search in. Like the filter, a search narrows whatever list is shown
 * until it is cleared; unlike it, nothing runs until Search (or the keyboard's search key),
 * as each search scans the library. The chosen fields are kept for next time. With a
 * search on, it opens with that query and offers Clear search, which ends it. Search and
 * Clear search are full-width buttons, as in Manage attachments; Cancel is the bar's.
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
        val searchButton = view.findViewById<Button>(R.id.button_search)
        val clearButton = view.findViewById<Button>(R.id.button_clear_search)

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

        val dialog = AlertDialog.Builder(context)
            .setTitle("Search")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .create()

        fun selectedFields() = boxes.filterValues { it.isChecked }.keys
        fun search() {
            val text = query.text.toString()
            if (text.isBlank() || selectedFields().isEmpty()) return
            preferences.setSearchFields(selectedFields())
            dialog.dismiss()
            onSearch(text.trim())
        }
        fun updateSearchButton() {
            searchButton.isEnabled = query.text.isNotBlank() && selectedFields().isNotEmpty()
        }

        boxes.values.forEach { box -> box.setOnCheckedChangeListener { _, _ -> updateSearchButton() } }
        query.doAfterTextChanged { updateSearchButton() }
        searchButton.setOnClickListener { search() }
        if (current.isNotEmpty()) {
            clearButton.visibility = View.VISIBLE
            clearButton.setOnClickListener {
                dialog.dismiss()
                onSearch("")
            }
        }
        updateSearchButton()
        // the keyboard's search key, or Enter on a hardware keyboard
        query.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId != EditorInfo.IME_ACTION_SEARCH && !enter) return@setOnEditorActionListener false
            if (event == null || event.action == KeyEvent.ACTION_DOWN) search()
            true
        }
        // the query has focus, so the keyboard comes up with the dialog
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        query.requestFocus()
        dialog.show()
    }
}
