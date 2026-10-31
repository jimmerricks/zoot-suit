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
    /** Off: no item-type icon, the text starts at the edge; selecting shows a tick column. */
    val typeIcons: Boolean = true,
    /** Zotero's coloured tags as dots, and emoji tags, before the title (TagColors). */
    val tagColors: Boolean = true,
) {
    companion object {
        const val KEY_DENSITY = "list_density"
        const val KEY_SECOND_LINE = "list_second_line"
        const val KEY_TYPE_ICONS = "list_type_icons"
        const val KEY_TAG_COLORS = "list_tag_colors"

        /** The settings this is read from; a change to any of them re-reads it. */
        val KEYS = setOf(KEY_DENSITY, KEY_SECOND_LINE, KEY_TYPE_ICONS, KEY_TAG_COLORS)

        fun from(prefs: SharedPreferences) = ListAppearance(
            compact = prefs.getString(KEY_DENSITY, "comfortable") == "compact",
            secondLine = ItemSubtitle.fromStored(prefs.getString(KEY_SECOND_LINE, null)),
            typeIcons = prefs.getBoolean(KEY_TYPE_ICONS, true),
            tagColors = prefs.getBoolean(KEY_TAG_COLORS, true),
        )
    }
}
