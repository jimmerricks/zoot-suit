package com.mickstarify.zooforzotero.LibraryActivity

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.R

/**
 * Sort order for the library list, laid out like [LibraryFilterDialog]. Choices only
 * change the dialog; Done saves them and re-sorts the list once (re-sorting a large
 * library on every tap was slow). Cancel (or Back) leaves the order as it was.
 * The order belongs to the library [groupId] (My Library = GroupInfo.NO_GROUP_ID).
 */
class LibrarySortDialog(
    private val context: Context,
    private val groupId: Int,
    private val onChange: () -> Unit,
) {
    private val preferences = PreferenceManager(context)

    fun show() {
        val initialMethod = preferences.getSortMethod(groupId)
        val initialAscending = preferences.isSortedAscendingly(groupId)

        val view: View = LayoutInflater.from(context).inflate(R.layout.dialog_library_sort, null)
        val methods = view.findViewById<RadioGroup>(R.id.radioGroup_sort_method)
        val order = view.findViewById<RadioGroup>(R.id.radioGroup_sort_order)

        // each method's button carries its preference value as its tag
        val methodButtons = (0 until methods.childCount).map { methods.getChildAt(it) as RadioButton }
        methodButtons.first { it.tag == preferences.sortMethodToString(initialMethod) }.isChecked = true
        order.check(if (initialAscending) R.id.radioButton_sort_ascending else R.id.radioButton_sort_descending)

        fun apply() {
            val method = preferences.stringToSortMethod(
                methods.findViewById<RadioButton>(methods.checkedRadioButtonId).tag as String
            )
            val ascending = order.checkedRadioButtonId == R.id.radioButton_sort_ascending
            if (method == initialMethod && ascending == initialAscending) return
            preferences.setSortMethod(groupId, method)
            preferences.setSortAscending(groupId, ascending)
            onChange()
        }

        AlertDialog.Builder(context)
            .setTitle("Sort")
            .setView(view)
            .setPositiveButton("Done") { _, _ -> apply() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
