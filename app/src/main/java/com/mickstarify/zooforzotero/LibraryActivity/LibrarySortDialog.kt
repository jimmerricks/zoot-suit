package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.common.DialogActions
import com.mickstarify.zooforzotero.common.DialogText

/**
 * Sort order for the library list, laid out like [LibraryFilterDialog]. Choices only
 * change the dialog; Sort (a full-width button, as in Manage attachments, enabled once
 * the choice differs) saves them and re-sorts the list once (re-sorting a large library
 * on every tap was slow). Cancel (or Back) leaves the order as it was.
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

        val builder = AlertDialog.Builder(context)
        // the dialog's theme, for the grey of the second lines
        val themed = builder.context
        val view: View = LayoutInflater.from(themed).inflate(R.layout.dialog_library_sort, null)
        val methods = view.findViewById<RadioGroup>(R.id.radioGroup_sort_method)
        val order = view.findViewById<RadioGroup>(R.id.radioGroup_sort_order)
        val sort = view.findViewById<Button>(R.id.button_apply_sort)
        view.findViewById<RadioButton>(R.id.radioButton_sort_ascending).text =
            DialogText.twoLines(themed, "Ascending", "A to Z, oldest first")
        view.findViewById<RadioButton>(R.id.radioButton_sort_descending).text =
            DialogText.twoLines(themed, "Descending", "Z to A, newest first")

        // each method's button carries its preference value as its tag
        val methodButtons = (0 until methods.childCount).map { methods.getChildAt(it) as RadioButton }
        methodButtons.first { it.tag == preferences.sortMethodToString(initialMethod) }.isChecked = true
        order.check(if (initialAscending) R.id.radioButton_sort_ascending else R.id.radioButton_sort_descending)

        fun method() = preferences.stringToSortMethod(
            methods.findViewById<RadioButton>(methods.checkedRadioButtonId).tag as String
        )
        fun ascending() = order.checkedRadioButtonId == R.id.radioButton_sort_ascending
        fun changed() = method() != initialMethod || ascending() != initialAscending

        val dialog = builder
            .setTitle("Sort")
            .setView(view)
            .show()
        DialogActions.leave(view, dialog, "Cancel")
        sort.isEnabled = false
        methods.setOnCheckedChangeListener { _, _ -> sort.isEnabled = changed() }
        order.setOnCheckedChangeListener { _, _ -> sort.isEnabled = changed() }
        sort.setOnClickListener {
            dialog.dismiss()
            if (!changed()) return@setOnClickListener
            preferences.setSortMethod(groupId, method())
            preferences.setSortAscending(groupId, ascending())
            onChange()
        }
    }
}
