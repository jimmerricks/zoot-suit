package com.mickstarify.zooforzotero.common

import android.content.Context
import android.util.TypedValue

/**
 * The colour the theme of this context gives [attr] (a colour or a colour list's default).
 * In a dialog, pass the dialog's context (AlertDialog.Builder.context): the activity's
 * theme has toolbar colours that a dialog doesn't use.
 */
fun Context.themeColor(attr: Int): Int {
    val value = TypedValue()
    theme.resolveAttribute(attr, value, true)
    return if (value.resourceId != 0) getColor(value.resourceId) else value.data
}
