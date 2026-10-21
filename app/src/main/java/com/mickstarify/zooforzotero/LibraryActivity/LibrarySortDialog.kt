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
 * Sort order for the library list, laid out and behaving like [LibraryFilterDialog]: every
 * tap is saved and applied at once through [onChange], so the list behind the dialog
 * follows. Cancel (or Back) restores the order the dialog opened with; Done keeps it.
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

        fun setOrder(ascending: Boolean) = preferences.setSortAscending(groupId, ascending)

        methods.setOnCheckedChangeListener { group, id ->
            preferences.setSortMethod(
                groupId, preferences.stringToSortMethod(group.findViewById<RadioButton>(id).tag as String)
            )
            onChange()
        }
        order.setOnCheckedChangeListener { _, id ->
            setOrder(id == R.id.radioButton_sort_ascending)
            onChange()
        }

        fun restore() {
            preferences.setSortMethod(groupId, initialMethod)
            setOrder(initialAscending)
            onChange()
        }

        AlertDialog.Builder(context)
            .setTitle("Sort")
            .setView(view)
            .setPositiveButton("Done", null)
            .setNegativeButton("Cancel") { _, _ -> restore() }
            .setOnCancelListener { restore() }
            .show()
    }
}
