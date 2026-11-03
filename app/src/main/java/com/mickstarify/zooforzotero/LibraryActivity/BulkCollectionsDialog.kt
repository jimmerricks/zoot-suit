package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mickstarify.zooforzotero.LibraryActivity.BulkChoices.State
import com.mickstarify.zooforzotero.R
import com.mickstarify.zooforzotero.ZoteroStorage.Database.Collection
import com.mickstarify.zooforzotero.common.DialogActions
import com.mickstarify.zooforzotero.common.DialogText
import com.mickstarify.zooforzotero.common.themeColor

/**
 * "Change collections" for the items selected in the list: adding them to collections and
 * taking them out, on one screen, as "Add and remove tags" does for tags. It lists the
 * library's collections as a tree, each with a three-state box (all of the items in it,
 * some, none); tapping one puts all of them in it, then takes all of them out, then leaves
 * it as it was. The field finds a collection by name; the matches are listed with their
 * parents named. Nothing changes until Apply, which makes every change in one go and says
 * how many it makes; Cancel (or Back) changes nothing. Only top-level items can be in
 * collections, so [count] leaves attachments and notes out.
 *
 * @param count the number of top-level items selected
 * @param collections the library's collections in tree order, each with its depth
 * @param counts how many of the items each collection holds, by key
 * @param open the collection the list shows, if any: the tree opens scrolled to it
 * @param onApply the keys of the collections to put the items in and of those to take them out of
 */
class BulkCollectionsDialog(
    private val context: Context,
    private val count: Int,
    private val collections: List<Pair<Collection, Int>>,
    counts: Map<String, Int>,
    private val open: String?,
    private val onApply: (add: List<String>, remove: List<String>) -> Unit,
) {
    private val choices = BulkChoices(count, counts)

    // each collection's parents, top first ("Fixture archive"), named under a match
    private val paths: Map<String, String> = run {
        val stack = ArrayList<String>()
        collections.associate { (c, depth) ->
            while (stack.size > depth) stack.removeAt(stack.size - 1)
            val path = stack.joinToString(" › ")
            stack.add(c.name)
            c.key to path
        }
    }

    private var rows: List<Pair<Collection, Int>> = collections
    private var query = ""

    fun show() {
        val builder = AlertDialog.Builder(context)
        // the dialog's theme, for the rows' colours
        val themed = builder.context
        val inflater = LayoutInflater.from(themed)
        val view = inflater.inflate(R.layout.dialog_bulk_collections, null)
        val apply = view.findViewById<Button>(R.id.button_apply_collections)
        val field = view.findViewById<EditText>(R.id.editText_bulk_collections)
        val empty = view.findViewById<TextView>(R.id.textView_bulk_collections_empty)
        val list = view.findViewById<RecyclerView>(R.id.recyclerView_bulk_collections)
        view.findViewById<TextView>(R.id.textView_bulk_collections_scope).text =
            DialogText.appliesTo(count, listed = false)
        view.findViewById<TextView>(R.id.textView_bulk_collections_hint).text =
            if (count == 1) "The collections it is in are ticked. Tap one to add it or take it out."
            else "Tap a collection to put all of them in it, again to take all of them out."
        list.layoutManager = LinearLayoutManager(themed)
        // a tap redraws the rows; no fading in and out for that
        list.itemAnimator = null

        val accent = themed.themeColor(androidx.appcompat.R.attr.colorAccent)
        val secondary = themed.themeColor(android.R.attr.textColorSecondary)
        val density = themed.resources.displayMetrics.density
        val start = (4 * density).toInt()
        val indent = (24 * density).toInt()

        lateinit var render: () -> Unit

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = inflater.inflate(R.layout.dialog_collection_choice, parent, false)
                return object : RecyclerView.ViewHolder(row) {}.apply {
                    itemView.setOnClickListener {
                        val (collection, _) = rows.getOrNull(bindingAdapterPosition) ?: return@setOnClickListener
                        choices.tap(collection.key)
                        render()
                    }
                }
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val row = holder.itemView as CheckedTextView
                val (collection, depth) = rows[position]
                val state = choices.state(collection.key)
                // matches are listed flat, their parents named instead
                val searching = query.isNotBlank()
                row.text = label(collection, searching, accent, secondary)
                row.isChecked = state != State.NONE
                row.setPaddingRelative(start + if (searching) 0 else indent * depth, row.paddingTop, row.paddingEnd, row.paddingBottom)
                row.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    when (state) {
                        State.ALL -> R.drawable.baseline_check_box_24
                        State.SOME -> R.drawable.baseline_indeterminate_check_box_24
                        State.NONE -> R.drawable.baseline_check_box_outline_blank_24
                    }, 0, 0, 0
                )
            }
        }
        list.adapter = adapter

        render = {
            val q = query.trim()
            rows = if (q.isEmpty()) collections
            else collections.filter { (c, _) -> c.name.contains(q, ignoreCase = true) }
            empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
            val changes = choices.changes
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
                list.scrollToPosition(0)
            }
        })
        // the keyboard's Done: put the keyboard away, to see the matches
        field.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(field.windowToken, 0)
            true
        }

        val dialog = builder
            .setTitle(context.getString(R.string.change_collections))
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
        // the open collection in view, a row above it for context
        open?.let { key -> collections.indexOfFirst { it.first.key == key } }
            ?.takeIf { it > 0 }
            ?.let { (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(it - 1, 0) }
    }

    /**
     * The collection's name; while [searching], its parents after it in grey; then what
     * Apply does to it, or how many of the items it holds now.
     */
    private fun label(collection: Collection, searching: Boolean, accent: Int, secondary: Int): CharSequence {
        val text = SpannableStringBuilder(collection.name)
        fun grey(part: String, color: Int) {
            val from = text.length
            text.append("  ").append(part)
            text.setSpan(ForegroundColorSpan(color), from, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        paths[collection.key]?.takeIf { searching && it.isNotEmpty() }?.let { grey("in $it", secondary) }
        val key = collection.key
        when {
            choices.isChanged(key) && choices.state(key) == State.ALL -> grey("add ${items(choices.affected(key))}", accent)
            choices.isChanged(key) -> grey("take out ${items(choices.affected(key))}", accent)
            choices.before(key) == State.SOME -> grey("%,d of %,d".format(choices.count(key), count), secondary)
        }
        return text
    }

    private fun items(n: Int) = if (n == 1) "1 item" else "%,d items".format(n)
}
