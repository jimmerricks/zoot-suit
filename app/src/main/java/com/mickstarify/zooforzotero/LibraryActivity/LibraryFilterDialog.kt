package com.mickstarify.zooforzotero.LibraryActivity

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import com.mickstarify.zooforzotero.R

/**
 * Filter for the library list: item and device conditions and tags, all ticked conditions
 * must hold. Ticks only change the dialog; the list is filtered when Done is tapped, since
 * redrawing a large library on every tick was slow. Clear removes the filter at once;
 * Cancel (or Back) leaves the list as it was.
 *
 * @param tagCounts the open library's tags with how many items carry each
 * @param tagColors the library's coloured tags: listed first, as in Zotero's tag selector
 * @param onApply sets the filter on the list
 */
class LibraryFilterDialog(
    private val context: Context,
    private val initial: LibraryFilter,
    private val tagCounts: List<Pair<String, Int>>,
    private val tagColors: TagColors,
    private val onApply: (LibraryFilter) -> Unit,
) {
    private var filter = initial
    private var query = ""
    private var shownTags: List<String> = emptyList()

    fun show() {
        val view: View = LayoutInflater.from(context).inflate(R.layout.dialog_library_filter, null)
        val hasPdf = view.findViewById<CheckBox>(R.id.checkBox_filter_has_pdf)
        val hasHtml = view.findViewById<CheckBox>(R.id.checkBox_filter_has_html)
        val hasOther = view.findViewById<CheckBox>(R.id.checkBox_filter_has_other)
        val hasNotes = view.findViewById<CheckBox>(R.id.checkBox_filter_has_notes)
        val onDevice = view.findViewById<CheckBox>(R.id.checkBox_filter_on_device)
        val htmlOnDevice = view.findViewById<CheckBox>(R.id.checkBox_filter_html_on_device)
        val edited = view.findViewById<CheckBox>(R.id.checkBox_filter_edited)
        val search = view.findViewById<EditText>(R.id.editText_tag_search)
        val list = view.findViewById<ListView>(R.id.listView_tags)
        val noTags = view.findViewById<TextView>(R.id.textView_no_tags)
        val adapter = ArrayAdapter<CharSequence>(context, R.layout.dialog_filter_tag_item)
        list.adapter = adapter
        val counts = tagCounts.toMap()

        fun render() {
            hasPdf.isChecked = filter.hasPdf
            hasHtml.isChecked = filter.hasHtml
            hasOther.isChecked = filter.hasOtherAttachment
            hasNotes.isChecked = filter.hasNotes
            onDevice.isChecked = filter.pdfOnDevice
            htmlOnDevice.isChecked = filter.htmlOnDevice
            edited.isChecked = filter.editedHere
            val all = (counts.keys + filter.tags).filter { it.contains(query.trim(), ignoreCase = true) }
            shownTags = order(all, filter.tags, tagColors)
            adapter.clear()
            adapter.addAll(shownTags.map { row(it, counts[it] ?: 0) })
            shownTags.forEachIndexed { i, tag -> list.setItemChecked(i, tag in filter.tags) }
            noTags.visibility = if (shownTags.isEmpty()) View.VISIBLE else View.GONE
            // as tall as its rows (36dp each, dialog_filter_tag_item), up to 300dp, then it scrolls
            val density = context.resources.displayMetrics.density
            list.layoutParams.height = (minOf(shownTags.size * 36, 300) * density).toInt()
            list.requestLayout()
        }

        fun update(newFilter: LibraryFilter) {
            filter = newFilter
            render()
        }

        hasPdf.setOnClickListener { update(filter.copy(hasPdf = hasPdf.isChecked)) }
        hasHtml.setOnClickListener { update(filter.copy(hasHtml = hasHtml.isChecked)) }
        hasOther.setOnClickListener { update(filter.copy(hasOtherAttachment = hasOther.isChecked)) }
        hasNotes.setOnClickListener { update(filter.copy(hasNotes = hasNotes.isChecked)) }
        onDevice.setOnClickListener { update(filter.copy(pdfOnDevice = onDevice.isChecked)) }
        htmlOnDevice.setOnClickListener { update(filter.copy(htmlOnDevice = htmlOnDevice.isChecked)) }
        edited.setOnClickListener { update(filter.copy(editedHere = edited.isChecked)) }
        list.setOnItemClickListener { _, _, position, _ ->
            val tag = shownTags[position]
            update(filter.copy(tags = if (tag in filter.tags) filter.tags - tag else filter.tags + tag))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                render()
            }
        })

        val dialog = AlertDialog.Builder(context)
            .setTitle("Filter")
            .setView(view)
            .setPositiveButton("Done") { _, _ -> if (filter != initial) onApply(filter) }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ -> if (initial.isActive) onApply(LibraryFilter()) }
            .create()
        // no keyboard until the tag search is tapped; it would cover half the dialog
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        render()
        dialog.show()
    }

    /** "tag (count)", a coloured tag after its dot; one with an emoji in its name has no dot, as in the list. */
    private fun row(tag: String, count: Int): CharSequence {
        val text = SpannableStringBuilder()
        val color = tagColors.colorOf(tag)?.takeUnless { TagColors.containsEmoji(tag) }
            ?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        if (color != null) {
            text.append("●")
            text.setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append(' ')
        }
        return text.append("$tag ($count)")
    }

    companion object {
        /**
         * The order of [tags] in the dialog: the coloured ones by their number, as at the top
         * of Zotero's tag selector, then the [selected] ones, then the rest, each by name.
         */
        fun order(tags: Collection<String>, selected: Set<String>, colors: TagColors): List<String> =
            tags.distinct().sortedWith(
                compareBy<String> { colors.position(it) ?: Int.MAX_VALUE }
                    .thenBy { it !in selected }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it }
            )
    }
}
