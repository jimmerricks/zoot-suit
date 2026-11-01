package com.mickstarify.zooforzotero.LibraryActivity

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.mickstarify.zooforzotero.R

/**
 * The add-tag dialog's buttons for the library's coloured tags, in Zotero's order (their
 * number), each with its colour's dot; one with an emoji in its name shows the emoji
 * instead, as in the list. Tapping one picks that tag, as typing it and Add would.
 */
object QuickTags {

    /** The coloured tags offered: all of [colors]' except those in [exclude] (already there). */
    fun offered(colors: TagColors, exclude: Set<String>): List<Pair<String, String>> =
        colors.colors.filter { (tag, _) -> tag !in exclude }

    /** Fills the dialog [view]'s chip row; it stays hidden if nothing is offered. */
    fun bind(view: View, colors: TagColors, exclude: Set<String>, onPick: (String) -> Unit) {
        val group = view.findViewById<ChipGroup>(R.id.chipGroup_coloured_tags)
        val offered = offered(colors, exclude)
        group.removeAllViews()
        group.visibility = if (offered.isEmpty()) View.GONE else View.VISIBLE
        for ((tag, color) in offered) {
            group.addView(Chip(view.context).apply {
                text = tag
                contentDescription = "Add tag $tag"
                val dot = if (TagColors.containsEmoji(tag)) null
                    else runCatching { Color.parseColor(color) }.getOrNull()
                if (dot != null) {
                    chipIcon = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(dot)
                    }
                    chipIconSize = resources.displayMetrics.density * 12
                    iconStartPadding = resources.displayMetrics.density * 4
                }
                setOnClickListener { onPick(tag) }
            })
        }
    }
}
