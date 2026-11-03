package com.mickstarify.zooforzotero.common

import android.app.Dialog
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import com.mickstarify.zooforzotero.R

/**
 * A dialog's buttons, under its body: the full-width ones that act (style DialogAction; the
 * first is the main act) and the one that leaves (Cancel or Close, [leave]), which stands in
 * for AlertDialog's button bar. Laid out the first way that fits, each label on one line:
 *
 * 1. all on one line: the leaving button at the end, the others from the start, the main
 *    act first; each at its own width, with the leaving button's padding beside its label
 *    (not stretched to fill the row);
 * 2. the acting buttons side by side in equal shares, the leaving one under them at the end,
 *    where the button bar would have it;
 * 3. the acting buttons stacked, each the whole width, the main act on top; the leaving one
 *    under them as in 2.
 *
 * The leaving button is always at the end, so it stays in one place whatever else shows
 * (Sync now hiding as a sync starts doesn't move Close). Decided when the dialog opens, from
 * its width and the labels then. While it is open the buttons only move down that list (a count in a label grows), never back up, so they don't
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

    // the acting buttons' own side padding (their style's), given back when they leave the
    // one-line arrangement, where they take the leaving button's
    private val ownPadding = HashMap<View, Pair<Int, Int>>()

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
        buttons.forEach { ownPadding.getOrPut(it) { it.paddingLeft to it.paddingRight } }
        // each label's width, then the button's with its own padding and with the leaving one's
        val labels = buttons.map { naturalWidth(it) - it.paddingLeft - it.paddingRight }
        val widths = buttons.mapIndexed { i, b -> labels[i] + ownPadding.getValue(b).let { it.first + it.second } }
        val leavePad = leave?.let { it.paddingLeft to it.paddingRight }
        val tight = buttons.mapIndexed { i, b ->
            maxOf(b.minimumWidth, labels[i] + (leavePad?.let { it.first + it.second } ?: 0))
        }

        fun share(room: Int) = (room - gap * (buttons.size - 1)) / buttons.size
        fun fits(room: Int) = buttons.isNotEmpty() && widths.all { it <= share(room) }
        val oneLine = leave != null && buttons.isNotEmpty() &&
                tight.sum() + gap * buttons.size + leaveWidth <= inner
        val best = when {
            oneLine -> Arrangement.ONE_LINE
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
        buttons.forEach { b ->
            val (left, right) = if (arrangement == Arrangement.ONE_LINE) leavePad!! else ownPadding.getValue(b)
            if (b.paddingLeft != left || b.paddingRight != right) b.setPadding(left, b.paddingTop, right, b.paddingBottom)
        }
        when (arrangement) {
            Arrangement.ONE_LINE, Arrangement.SIDE_BY_SIDE -> {
                val specs = buttons.mapIndexed { i, _ ->
                    val w = if (arrangement == Arrangement.ONE_LINE) tight[i] else share(inner)
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
                }
                buttons.forEachIndexed { i, b -> b.measure(specs[i], unspecified) }
                val tallest = buttons.maxOf { it.measuredHeight }
                // the same height, should one label wrap after all (a font scale change)
                val tall = MeasureSpec.makeMeasureSpec(tallest, MeasureSpec.EXACTLY)
                buttons.forEachIndexed { i, b -> b.measure(specs[i], tall) }
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
                    val end = r - l - paddingRight
                    val top = y + (row - leave.measuredHeight) / 2
                    leave.layout(end - leave.measuredWidth, top, end, top + leave.measuredHeight)
                }
                // one line: from the start, the main act first; side by side: the main act
                // at the end, over the leaving button
                (if (arrangement == Arrangement.ONE_LINE) buttons else buttons.asReversed()).forEach {
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

        /**
         * Keeps [dialog]'s bottom edge, and so its buttons, where it first opened (centred):
         * as its content changes (the sync dialog's edits, then stages, then the finished
         * sync) it grows or shrinks upwards, instead of re-centring and moving the buttons.
         * Call before show().
         */
        fun keepButtonsInPlace(dialog: Dialog) {
            dialog.setOnShowListener {
                val window = dialog.window ?: return@setOnShowListener
                val decor = window.decorView
                decor.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (decor.height == 0) return
                        decor.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        val frame = Rect().also { decor.getWindowVisibleDisplayFrame(it) }
                        val at = IntArray(2).also { decor.getLocationOnScreen(it) }
                        val gap = frame.bottom - (at[1] + decor.height)
                        if (gap <= 0) return
                        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                        window.attributes = window.attributes.apply { y = gap }
                    }
                })
            }
        }
    }
}
