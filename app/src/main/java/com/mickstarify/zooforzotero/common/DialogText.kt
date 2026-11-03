package com.mickstarify.zooforzotero.common

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/** Text that the app's dialogs share (the styles are DialogLead, DialogHint, DialogAction). */
object DialogText {
    /**
     * The dialog's lead line, what it acts on: [count] items, all those the list shows
     * ([listed]) or those selected. "Applies to 10,306 items: all those listed".
     */
    fun appliesTo(count: Int, listed: Boolean): String {
        val items = if (count == 1) "1 item" else "%,d items".format(count)
        return when {
            listed && count == 1 -> "Applies to 1 item: the one listed"
            listed -> "Applies to $items: all those listed"
            count == 1 -> "Applies to 1 item: the one selected"
            else -> "Applies to $items: those selected"
        }
    }

    /**
     * A choice's [label], with [detail] under it in grey, as the kinds in Manage
     * attachments. [context]: the dialog's (AlertDialog.Builder.context), for its grey.
     */
    fun twoLines(context: Context, label: CharSequence, detail: CharSequence): CharSequence =
        SpannableStringBuilder(label).apply {
            val start = length
            append("\n").append(detail)
            setSpan(
                ForegroundColorSpan(context.themeColor(android.R.attr.textColorSecondary)),
                start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

    /** "1 file", "6,183 files". */
    fun files(n: Int) = if (n == 1) "1 file" else "%,d files".format(n)
}
