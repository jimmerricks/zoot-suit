package com.mickstarify.zooforzotero.LibraryActivity

import android.content.SharedPreferences

/**
 * How the library list's rows look: Settings → Appearance. Read whenever one of [KEYS]
 * changes (LibraryListFragment), so a change shows on returning from Settings.
 */
data class ListAppearance(
    /** One-line titles and less space around them; more rows fit on a screen. */
    val compact: Boolean = false,
    val secondLine: ItemSubtitle = ItemSubtitle.CREATOR,
) {
    companion object {
        const val KEY_DENSITY = "list_density"
        const val KEY_SECOND_LINE = "list_second_line"

        /** The settings this is read from; a change to any of them re-reads it. */
        val KEYS = setOf(KEY_DENSITY, KEY_SECOND_LINE)

        fun from(prefs: SharedPreferences) = ListAppearance(
            compact = prefs.getString(KEY_DENSITY, "comfortable") == "compact",
            secondLine = ItemSubtitle.fromStored(prefs.getString(KEY_SECOND_LINE, null)),
        )
    }
}
