package com.mickstarify.zooforzotero.LibraryActivity

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import com.mickstarify.zooforzotero.PreferenceManager
import com.mickstarify.zooforzotero.R

class LibraryFilterMenuDialog(val context: Context, val onFilterChange: (() -> (Unit))) {
    lateinit var preferences: PreferenceManager

    var selected_sorting_method = "UNSET"

    fun setSortingMethod(index: Int) {
        try {
            selected_sorting_method =
                context.resources.getStringArray(R.array.sort_options_values)[index]
        } catch (e: ArrayIndexOutOfBoundsException) {
            Log.e("zotero", "Error array out of index for LibraryFilterDialog")
        }
    }

    private fun saveSettings() {
        preferences.setSortMethod(selected_sorting_method)
        onFilterChange()
    }

    private fun getSortString(method: String): String {
        val i = context.resources.getStringArray(R.array.sort_options_values).indexOf(method)
        if (i == -1) {
            val params = Bundle()
            params.putString("method", method)
            return "Error"
        }
        return context.resources.getTextArray(R.array.sort_options_entries)[i].toString()
    }

    var sortingOrderButton: ImageButton? = null

    fun setSortButtonAscending() {
        sortingOrderButton?.apply {
            this.contentDescription = "Sort Descendingly"
            this.setImageResource(R.drawable.ic_arrow_upward_24px)
        }
    }

    fun setSortButtonDescending() {
        sortingOrderButton?.apply {
            this.contentDescription = "Sort descendingly"
            this.setImageResource(R.drawable.ic_arrow_downward_24px)
        }
    }

    fun show() {
        val dialogBuilder = AlertDialog.Builder(context).create()
        val inflater = LayoutInflater.from(context)
        val dialogView: View = inflater.inflate(R.layout.dialog_filter_menu, null)

        val sortingMethodButton = dialogView.findViewById<Button>(R.id.button_sort_by)
        sortingMethodButton.text = this.getSortString(selected_sorting_method)

        val builder = AlertDialog.Builder(context)
        builder.setTitle("Sorting Method")
        builder.setItems(context.resources.getTextArray(R.array.sort_options_entries),
            DialogInterface.OnClickListener
            { dialogInterface, i ->
                this.setSortingMethod(i)
                sortingMethodButton.text = this.getSortString(this.selected_sorting_method)

            })

        sortingMethodButton.setOnClickListener { builder.show() }

        val cancelButton = dialogView.findViewById<Button>(R.id.btn_cancel)
        val submitButton = dialogView.findViewById<Button>(R.id.btn_submit)

        cancelButton.setOnClickListener {
            dialogBuilder.dismiss()
        }

        submitButton.setOnClickListener {
            saveSettings()
            dialogBuilder.dismiss()
        }

        sortingOrderButton = dialogView.findViewById<ImageButton>(R.id.button_sort_order)
        if (preferences.isSortedAscendingly()) {
            setSortButtonAscending()
        } else {
            setSortButtonDescending()
        }

        sortingOrderButton?.setOnClickListener {
            if (preferences.isSortedAscendingly()) {
                setSortButtonDescending()
                preferences.setSortDirection(PreferenceManager.SORT_METHOD_DESCENDING)
            } else {
                setSortButtonAscending()
                preferences.setSortDirection(PreferenceManager.SORT_METHOD_ASCENDING)
            }
        }


        dialogBuilder.setView(dialogView)

        //not letting user dismiss dialog because otherwise the keyboard stays and it's a pain to
        //dismiss it. (need to find currentFocusedView, etc)
        dialogBuilder.setCanceledOnTouchOutside(false)

        dialogBuilder.show()
    }


    init {
        preferences = PreferenceManager(context)
        selected_sorting_method =
            preferences.sortMethodToString(preferences.getSortMethod()) //terrible code, i know.

    }

}