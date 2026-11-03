package com.mickstarify.zooforzotero.common

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mickstarify.zooforzotero.R

/**
 * A question with its answers as full-width buttons, as in Manage attachments: the main
 * answer filled, an [other] one outlined, Cancel alone in the bar. For questions whose
 * answers need more words than the button bar holds on a phone (it stacks three).
 * Each answer is a label and what it does; the dialog closes as it acts.
 */
object ConfirmDialog {
    fun show(
        context: Context,
        title: String,
        message: CharSequence,
        main: Pair<String, () -> Unit>,
        other: Pair<String, () -> Unit>? = null,
    ) {
        val builder = AlertDialog.Builder(context)
        val view = LayoutInflater.from(builder.context).inflate(R.layout.dialog_confirm, null)
        view.findViewById<TextView>(R.id.textView_confirm_message).text = message
        val dialog = builder
            .setTitle(title)
            .setView(view)
            .setNegativeButton("Cancel", null)
            .create()
        fun bind(id: Int, answer: Pair<String, () -> Unit>) = view.findViewById<Button>(id).apply {
            visibility = View.VISIBLE
            text = answer.first
            setOnClickListener {
                dialog.dismiss()
                answer.second()
            }
        }
        bind(R.id.button_confirm, main)
        other?.let { bind(R.id.button_confirm_other, it) }
        dialog.show()
    }
}
