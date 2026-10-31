package com.mickstarify.zooforzotero.LibraryActivity

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mickstarify.zooforzotero.R

/**
 * Filter for the library list: item and device conditions and tags, all ticked conditions
 * must hold. One list scrolls them all; while the tag search is in use, it keeps its section
 * at the top with the matching tags below, above the keyboard. Ticks only change the dialog; the list is filtered when Done is tapped, since
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
        val inflater = LayoutInflater.from(context)
        val list = inflater.inflate(R.layout.dialog_library_filter, null) as RecyclerView
        // before the header is inflated: its layout params come from the layout manager
        val layout = LinearLayoutManager(context)
        list.layoutManager = layout
        val header = inflater.inflate(R.layout.dialog_library_filter_header, list, false)
        val hasPdf = header.findViewById<CheckBox>(R.id.checkBox_filter_has_pdf)
        val hasHtml = header.findViewById<CheckBox>(R.id.checkBox_filter_has_html)
        val hasOther = header.findViewById<CheckBox>(R.id.checkBox_filter_has_other)
        val hasNotes = header.findViewById<CheckBox>(R.id.checkBox_filter_has_notes)
        val onDevice = header.findViewById<CheckBox>(R.id.checkBox_filter_on_device)
        val htmlOnDevice = header.findViewById<CheckBox>(R.id.checkBox_filter_html_on_device)
        val edited = header.findViewById<CheckBox>(R.id.checkBox_filter_edited)
        val tagsHeader = header.findViewById<View>(R.id.textView_tags_header)
        val search = header.findViewById<EditText>(R.id.editText_tag_search)
        val noTags = header.findViewById<TextView>(R.id.textView_no_tags)
        val counts = tagCounts.toMap()
        // dialog_filter_tag_item
        val rowHeight = (36 * context.resources.displayMetrics.density).toInt()

        // while the search is in use its section stays at the top, the matches below it
        fun searching() = search.hasFocus()
        fun searchToTop() = layout.scrollToPositionWithOffset(0, -tagsHeader.top)
        // the blank after the tags: tall enough for the search to reach the top over few matches
        fun spacerHeight(): Int {
            if (!searching()) return 0
            val below = header.height - tagsHeader.top + shownTags.size * rowHeight
            return maxOf(0, list.height - list.paddingTop - list.paddingBottom - below)
        }

        lateinit var update: (LibraryFilter) -> Unit
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = shownTags.size + 2
            override fun getItemViewType(position: Int) = when (position) {
                0 -> HEADER
                itemCount - 1 -> SPACER
                else -> TAG
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = when (viewType) {
                    HEADER -> header
                    SPACER -> View(context).apply { layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, 0) }
                    else -> inflater.inflate(R.layout.dialog_filter_tag_item, parent, false)
                }
                return object : RecyclerView.ViewHolder(view) {}.apply {
                    // the one header, with the search field that may have the keyboard
                    if (viewType == HEADER) setIsRecyclable(false)
                    if (viewType == TAG) itemView.setOnClickListener {
                        val tag = shownTags.getOrNull(bindingAdapterPosition - 1) ?: return@setOnClickListener
                        update(filter.copy(tags = if (tag in filter.tags) filter.tags - tag else filter.tags + tag))
                    }
                }
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                when (holder.itemViewType) {
                    TAG -> (holder.itemView as CheckedTextView).apply {
                        val tag = shownTags[position - 1]
                        text = row(tag, counts[tag] ?: 0)
                        isChecked = tag in filter.tags
                    }
                    SPACER -> holder.itemView.layoutParams.height = spacerHeight()
                }
            }
        }
        list.adapter = adapter
        // a tick redraws the rows; no fading in and out for that
        list.itemAnimator = null

        // the header row stays as it is; the tags and the blank after them are redrawn
        fun render() {
            hasPdf.isChecked = filter.hasPdf
            hasHtml.isChecked = filter.hasHtml
            hasOther.isChecked = filter.hasOtherAttachment
            hasNotes.isChecked = filter.hasNotes
            onDevice.isChecked = filter.pdfOnDevice
            htmlOnDevice.isChecked = filter.htmlOnDevice
            edited.isChecked = filter.editedHere
            val before = shownTags.size
            val all = (counts.keys + filter.tags).filter { it.contains(query.trim(), ignoreCase = true) }
            shownTags = order(all, filter.tags, tagColors)
            noTags.visibility = if (shownTags.isEmpty()) View.VISIBLE else View.GONE
            val after = shownTags.size
            adapter.notifyItemRangeChanged(1, minOf(before, after))
            if (after > before) adapter.notifyItemRangeInserted(before + 1, after - before)
            if (after < before) adapter.notifyItemRangeRemoved(after + 1, before - after)
            adapter.notifyItemChanged(after + 1)
            if (searching()) list.post { searchToTop() }
        }

        update = { newFilter ->
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
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                render()
            }
        })
        val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        // the keyboard's Done, or Enter on a hardware keyboard: the keyboard goes, the
        // conditions come back above the tags
        search.setOnEditorActionListener { _, action, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (action != EditorInfo.IME_ACTION_DONE && !enter) return@setOnEditorActionListener false
            if (enter && event?.action != KeyEvent.ACTION_DOWN) return@setOnEditorActionListener true
            keyboard.hideSoftInputFromWindow(search.windowToken, 0)
            // to the row itself: otherwise the first checkbox takes the focus and its highlight
            header.requestFocus()
            true
        }
        search.setOnFocusChangeListener { _, _ ->
            adapter.notifyItemChanged(adapter.itemCount - 1)
            list.post { if (searching()) searchToTop() else layout.scrollToPosition(0) }
        }
        // the keyboard coming or going resizes the dialog: the blank and the scroll follow
        list.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop && searching()) list.post {
                adapter.notifyItemChanged(adapter.itemCount - 1)
                searchToTop()
            }
        }
        // scrolling through the matches puts the keyboard away, as it would scroll the field off
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && searching()) {
                    keyboard.hideSoftInputFromWindow(search.windowToken, 0)
                }
            }
        })

        val dialog = AlertDialog.Builder(context)
            .setTitle("Filter")
            .setView(list)
            .setPositiveButton("Done") { _, _ -> if (filter != initial) onApply(filter) }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ -> if (initial.isActive) onApply(LibraryFilter()) }
            .create()
        // no keyboard until the tag search is tapped; with it, the dialog shrinks above it
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        render()
        dialog.show()
        // AlertDialog looked for a text field as it showed, before the list had its rows,
        // found none, and kept the window from the keyboard
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
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
        private const val HEADER = 0
        private const val TAG = 1
        private const val SPACER = 2

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
