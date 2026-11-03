package com.mickstarify.zooforzotero.common

import android.app.Dialog
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import com.mickstarify.zooforzotero.R

/**
 * A dialog's buttons, under its body: the full-width ones that act (style DialogAction; the
 * first is the main act) and the one that leaves (Cancel or Close, [leave]), which stands in
 * for AlertDialog's button bar. Laid out the first way that fits, each label on one line:
 *
 * 1. all on one line: the leaving button at its own width at the start, the others sharing
 *    the rest equally, the main act at the end;
 * 2. the acting buttons side by side in equal shares, the leaving one under them at the end,
 *    where the button bar would have it;
 * 3. the acting buttons stacked, each the whole width, the main act on top; the leaving one
 *    under them as in 2.
 *
 * Decided when the dialog opens, from its width and the labels then. While it is open the
 * buttons only move down that list (a count in a label grows), never back up, so they don't
 * jump about as the user ticks things. A new width (rotation) or another button shown or
 * hidden decides afresh.
 */
class DialogActions @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private enum class Arrangement { ONE_LINE, SIDE_BY_SIDE, STACKED }

    private val gap = (8 * resources.displayMetrics.density).toInt()

    private var leaveButton: View? = null
    private var decidedFor: Triple<Int, Int, Boolean>? = null
    private var arrangement = Arrangement.STACKED

    /**
     * Shows the button that leaves the dialog, [label] (Cancel or Close), styled as the
     * button bar's; tapping it does [onLeave]. Set it instead of AlertDialog's negative button.
     */
    fun leave(label: String, onLeave: () -> Unit) {
        leaveButton?.let { removeView(it) }
        val button = MaterialButton(context, null, androidx.appcompat.R.attr.buttonBarNegativeButtonStyle).apply {
            text = label
            setOnClickListener { onLeave() }
        }
        leaveButton = button
        addView(button)
    }

    /** The acting buttons shown, in order (the main act first). */
    private fun acting() = (0 until childCount).map { getChildAt(it) }
        .filter { it !== leaveButton && it.visibility != GONE }

    private fun shownLeave() = leaveButton?.takeIf { it.visibility != GONE }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = width - paddingLeft - paddingRight
        val buttons = acting()
        val leave = shownLeave()
        val leaveWidth = leave?.let { naturalWidth(it) } ?: 0
        val widths = buttons.map { naturalWidth(it) }

        fun share(room: Int) = (room - gap * (buttons.size - 1)) / buttons.size
        fun fits(room: Int) = buttons.isNotEmpty() && widths.all { it <= share(room) }
        val best = when {
            leave != null && fits(inner - leaveWidth - gap) -> Arrangement.ONE_LINE
            buttons.size > 1 && fits(inner) -> Arrangement.SIDE_BY_SIDE
            else -> Arrangement.STACKED
        }
        val now = Triple(width, buttons.size, leave != null)
        if (decidedFor != now || best > arrangement) {
            decidedFor = now
            arrangement = best
        }

        val unspecified = MeasureSpec.UNSPECIFIED
        var height = 0
        when (arrangement) {
            Arrangement.ONE_LINE, Arrangement.SIDE_BY_SIDE -> {
                val room = if (arrangement == Arrangement.ONE_LINE) inner - leaveWidth - gap else inner
                val each = MeasureSpec.makeMeasureSpec(share(room), MeasureSpec.EXACTLY)
                buttons.forEach { it.measure(each, unspecified) }
                val tallest = buttons.maxOf { it.measuredHeight }
                // the same height, should one label wrap after all (a font scale change)
                val tall = MeasureSpec.makeMeasureSpec(tallest, MeasureSpec.EXACTLY)
                buttons.forEach { it.measure(each, tall) }
                height = tallest
            }
            Arrangement.STACKED -> {
                val each = MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY)
                buttons.forEach {
                    it.measure(each, unspecified)
                    height += it.measuredHeight
                }
            }
        }
        if (leave != null) {
            leave.measure(MeasureSpec.makeMeasureSpec(leaveWidth, MeasureSpec.EXACTLY), unspecified)
            height = if (arrangement == Arrangement.ONE_LINE) maxOf(height, leave.measuredHeight)
            else height + leave.measuredHeight
        }
        // nothing shown (Sync now during a sync, no leaving button): no gap above nothing
        val total = if (height == 0) 0 else height + paddingTop + paddingBottom
        setMeasuredDimension(width, resolveSize(total, heightMeasureSpec))
    }

    /** The button's width with its label on one line. */
    private fun naturalWidth(button: View): Int {
        button.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        return button.measuredWidth
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val buttons = acting()
        val leave = shownLeave()
        var y = paddingTop
        when (arrangement) {
            Arrangement.ONE_LINE, Arrangement.SIDE_BY_SIDE -> {
                val row = buttons.maxOf { it.measuredHeight }
                var x = paddingLeft
                if (arrangement == Arrangement.ONE_LINE && leave != null) {
                    val top = y + (row - leave.measuredHeight) / 2
                    leave.layout(x, top, x + leave.measuredWidth, top + leave.measuredHeight)
                    x += leave.measuredWidth + gap
                }
                // the main act at the end
                buttons.asReversed().forEach {
                    it.layout(x, y, x + it.measuredWidth, y + it.measuredHeight)
                    x += it.measuredWidth + gap
                }
                y += row
            }
            Arrangement.STACKED -> buttons.forEach {
                it.layout(paddingLeft, y, paddingLeft + it.measuredWidth, y + it.measuredHeight)
                y += it.measuredHeight
            }
        }
        if (leave != null && arrangement != Arrangement.ONE_LINE) {
            val end = r - l - paddingRight
            leave.layout(end - leave.measuredWidth, y, end, y + leave.measuredHeight)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?) = LayoutParams(context, attrs)
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p != null

    companion object {
        /**
         * Gives [dialog] its leaving button [label] in [view]'s DialogActions (id
         * dialog_actions): tapping it does [onLeave], then closes the dialog.
         */
        fun leave(view: View, dialog: Dialog, label: String, onLeave: () -> Unit = {}) {
            view.findViewById<DialogActions>(R.id.dialog_actions).leave(label) {
                onLeave()
                dialog.dismiss()
            }
        }
    }
}
