package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mickstarify.zooforzotero.LibraryActivity.BulkTagChoices.State
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.common.DialogActions
import com.mickstarify.zooforzotero.common.DialogText
import com.mickstarify.zooforzotero.common.themeColor
import com.mickstarify.zooforzotero.ZoteroStorage.ItemEdits.ItemEdits

/**
 * "Add and remove tags" for the items selected in the list: one screen for both. It lists
 * the tags on the items, each with a three-state box (on all of them, some, none), the
 * library's coloured tags first as in Zotero. Tapping a tag puts it on all of the items,
 * then takes it off all of them, then leaves it as it was. The field finds a tag of the
 * library, or adds a new one. Laid out like Manage attachments: what it applies to, how it
 * works, then the tags, and Apply as a full-width button that says how many changes it
 * makes. Nothing changes until Apply, which makes every change in one go; Cancel (or
 * Back) changes nothing.
 *
 * @param count the number of items selected
 * @param onItems the tags on the selected items, each with how many of them carry it
 * @param colors the library's coloured tags
 * @param onApply the tags to put on all of the items and those to take off them all
 * @return gives the dialog the library's tags once they're read, for the field to find
 */
class BulkTagsDialog(
    private val context: Context,
    private val count: Int,
    onItems: List<Pair<String, Int>>,
    private val colors: TagColors,
    private val onApply: (add: List<String>, remove: List<String>) -> Unit,
) {
    private val choices = BulkTagChoices(count, onItems.toMap())
    private var libraryTags: List<String> = emptyList()
    private var query = ""

    private sealed class Row {
        data class New(val tag: String) : Row()
        data class Tag(val tag: String) : Row()
    }

    private var rows: List<Row> = emptyList()

    fun show(): (List<String>) -> Unit {
        val builder = AlertDialog.Builder(context)
        // the dialog's theme, for the rows' colours
        val themed = builder.context
        val inflater = LayoutInflater.from(themed)
        val view = inflater.inflate(R.layout.dialog_bulk_tags, null)
        val scope = view.findViewById<TextView>(R.id.textView_bulk_tags_scope)
        val hint = view.findViewById<TextView>(R.id.textView_bulk_tags_hint)
        val apply = view.findViewById<Button>(R.id.button_apply_tags)
        val field = view.findViewById<EditText>(R.id.editText_bulk_tags)
        val empty = view.findViewById<TextView>(R.id.textView_bulk_tags_empty)
        val list = view.findViewById<RecyclerView>(R.id.recyclerView_bulk_tags)
        list.layoutManager = LinearLayoutManager(themed)
        // a tap redraws the rows; no fading in and out for that
        list.itemAnimator = null

        val accent = themed.themeColor(androidx.appcompat.R.attr.colorAccent)
        val secondary = themed.themeColor(android.R.attr.textColorSecondary)
        scope.text = DialogText.appliesTo(count, listed = false)
        hint.text = if (count == 1) "Its tags. Tap one to add or remove it."
        else "Their tags. Tap one to put it on all of them, again to take it off all of them."

        lateinit var dialog: AlertDialog
        lateinit var render: () -> Unit

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = inflater.inflate(R.layout.dialog_bulk_tag_item, parent, false)
                return object : RecyclerView.ViewHolder(row) {}.apply {
                    itemView.setOnClickListener {
                        when (val r = rows.getOrNull(bindingAdapterPosition) ?: return@setOnClickListener) {
                            is Row.New -> {
                                choices.add(r.tag)
                                field.text.clear() // renders
                            }
                            is Row.Tag -> {
                                choices.tap(r.tag)
                                render()
                            }
                        }
                    }
                }
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val row = holder.itemView as CheckedTextView
                when (val r = rows[position]) {
                    is Row.New -> {
                        row.text = "Add “${r.tag}”"
                        row.isChecked = false
                        row.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.baseline_add_24, 0, 0, 0)
                    }
                    is Row.Tag -> {
                        val state = choices.state(r.tag)
                        row.text = label(r.tag, accent, secondary)
                        row.isChecked = state != State.NONE
                        row.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            when (state) {
                                State.ALL -> R.drawable.baseline_check_box_24
                                State.SOME -> R.drawable.baseline_indeterminate_check_box_24
                                State.NONE -> R.drawable.baseline_check_box_outline_blank_24
                            }, 0, 0, 0
                        )
                    }
                }
            }
        }
        list.adapter = adapter

        render = {
            val q = query.trim()
            val listed = choices.tags() + colors.colors.map { it.first }
            val tags = if (q.isEmpty()) listed
            else (listed + libraryTags).filter { it.contains(q, ignoreCase = true) }
            val ordered = LibraryFilterDialog.order(tags, choices.tags(), colors)
            val typed = ItemEdits.cleanTag(q)?.takeIf { it !in ordered && it !in libraryTags }
            rows = listOfNotNull(typed?.let { Row.New(it) }) + ordered.map { Row.Tag(it) }
            empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            empty.text = if (q.isEmpty()) {
                if (count == 1) "The item has no tags. Type one to add it." else "None of the items has tags. Type one to add it."
            } else "No matching tags"
            adapter.notifyDataSetChanged()
            val changes = choices.toAdd.size + choices.toRemove.size
            apply.isEnabled = changes > 0
            apply.text = when (changes) {
                0 -> "Apply"
                1 -> "Apply 1 change"
                else -> "Apply %,d changes".format(changes)
            }
        }

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                render()
            }
        })
        // the keyboard's Done or Enter: the tag typed (as found, if the library has it) goes on all of them
        field.setOnEditorActionListener { _, action, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (action != EditorInfo.IME_ACTION_DONE && !enter) return@setOnEditorActionListener false
            if (enter && event?.action != KeyEvent.ACTION_DOWN) return@setOnEditorActionListener true
            val tag = ItemEdits.cleanTag(field.text.toString())
            if (tag == null) {
                if (field.text.isNotBlank()) field.error = "At most ${ItemEdits.MAX_TAG_LENGTH} characters"
            } else {
                choices.add(tag)
                field.text.clear()
            }
            true
        }

        dialog = builder
            .setTitle(context.getString(R.string.add_and_remove_tags))
            .setView(view)
            .create()
        DialogActions.leave(view, dialog, "Cancel")
        apply.setOnClickListener {
            dialog.dismiss()
            if (choices.hasChanges) onApply(choices.toAdd, choices.toRemove)
        }
        // the keyboard only once the field is tapped; with it, the dialog shrinks above it
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.show()
        render()
        return { tags ->
            libraryTags = tags
            if (query.isNotBlank()) render()
        }
    }

    /** The tag (after its colour's dot), then what Apply does to it, or how many have it now. */
    private fun label(tag: String, accent: Int, secondary: Int): CharSequence {
        val text = LibraryFilterDialog.tagLabel(tag, colors)
        val note = when {
            choices.isChanged(tag) && choices.state(tag) == State.ALL -> "add to ${items(choices.affected(tag))}"
            choices.isChanged(tag) -> "remove from ${items(choices.affected(tag))}"
            choices.before(tag) == State.SOME -> "%,d of %,d".format(choices.count(tag), count)
            else -> null
        } ?: return text
        val start = text.length
        text.append("  ").append(note)
        text.setSpan(
            ForegroundColorSpan(if (choices.isChanged(tag)) accent else secondary),
            start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return text
    }

    private fun items(n: Int) = if (n == 1) "1 item" else "%,d items".format(n)
}
