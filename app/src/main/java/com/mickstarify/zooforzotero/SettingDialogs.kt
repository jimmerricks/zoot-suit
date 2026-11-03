package com.mickstarify.zooforzotero

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputLayout
import com.mickstarify.zooforzotero.common.DialogActions

/**
 * Settings' dialogs for a value, in place of androidx.preference's stock ones, built like
 * the library's: the choices or the field, then a full-width button that names what it
 * does and Cancel at the right (DialogActions). Nothing changes until that button.
 */
object SettingDialogs {

    /**
     * A choice among [entries] (labels), [current] the index set now (-1 for none); Apply,
     * enabled once another is ticked, calls [onChoose] with the index.
     */
    fun choose(context: Context, title: CharSequence, entries: List<CharSequence>, current: Int, onChoose: (Int) -> Unit) {
        val builder = AlertDialog.Builder(context)
        val view = LayoutInflater.from(builder.context).inflate(R.layout.dialog_setting_choice, null)
        val group = view.findViewById<RadioGroup>(R.id.radioGroup_setting_choices)
        val apply = view.findViewById<Button>(R.id.button_setting_apply)
        val minHeight = (36 * context.resources.displayMetrics.density).toInt()
        val ids = entries.map { label ->
            RadioButton(builder.context).apply {
                id = View.generateViewId()
                text = label
                this.minHeight = minHeight
                group.addView(this, RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT)
            }.id
        }
        if (current in ids.indices) group.check(ids[current])
        val dialog = builder.setTitle(title).setView(view).create()
        DialogActions.leave(view, dialog, "Cancel")
        fun chosen() = ids.indexOf(group.checkedRadioButtonId)
        apply.isEnabled = false
        group.setOnCheckedChangeListener { _, _ -> apply.isEnabled = chosen() >= 0 && chosen() != current }
        apply.setOnClickListener {
            dialog.dismiss()
            onChoose(chosen())
        }
        dialog.show()
    }

    /**
     * A value typed in: [hint] says what it does, [text] is the value now, [inputType] the
     * field's. Save, enabled once the text differs, calls [onSave] if [check] finds no
     * mistake (it returns what is wrong, shown under the field, or null).
     */
    fun edit(
        context: Context, title: CharSequence, hint: CharSequence?, text: String, inputType: Int,
        check: (String) -> String? = { null }, onSave: (String) -> Unit,
    ) {
        val builder = AlertDialog.Builder(context)
        val view = LayoutInflater.from(builder.context).inflate(R.layout.dialog_setting_text, null)
        view.findViewById<TextView>(R.id.textView_setting_hint).apply {
            this.text = hint
            visibility = if (hint.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        val layout = view.findViewById<TextInputLayout>(R.id.textInputLayout_setting)
        val field = layout.editText!!
        field.inputType = inputType
        field.setText(text)
        field.setSelection(text.length)
        val save = view.findViewById<Button>(R.id.button_setting_save)
        val dialog = builder.setTitle(title).setView(view).create()
        DialogActions.leave(view, dialog, "Cancel")
        save.isEnabled = false
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                layout.error = null
                save.isEnabled = s.toString() != text
            }
        })
        fun trySave() {
            val value = field.text.toString().trim()
            val mistake = check(value)
            if (mistake != null) {
                layout.error = mistake
                return
            }
            dialog.dismiss()
            onSave(value)
        }
        save.setOnClickListener { trySave() }
        field.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            if (save.isEnabled) trySave()
            true
        }
        // the keyboard comes up with the field; the dialog shrinks above it
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.show()
        field.requestFocus()
    }
}
