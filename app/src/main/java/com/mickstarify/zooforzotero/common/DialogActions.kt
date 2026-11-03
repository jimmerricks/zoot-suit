package com.mickstarify.zooforzotero.common

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * The full-width buttons under a dialog's body (style DialogAction): side by side, in equal
 * shares of the width, when each label fits its share on one line; else stacked, each the
 * whole width. Side by side, the first button (the main act) is at the end, as in a button
 * bar; stacked, it is on top. Decided when the dialog opens, from the dialog's width and the
 * labels then. While open, the buttons go from side by side to stacked if a label grows too
 * long (a count in it rises), but not back, so they don't jump about as the user ticks
 * things. A new width (rotation) or another button shown or hidden decides afresh.
 */
class DialogActions @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val gap = (8 * resources.displayMetrics.density).toInt()

    private var decidedFor: Pair<Int, Int>? = null
    private var sideBySide = false

    private fun shown() = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = width - paddingLeft - paddingRight
        val buttons = shown()
        val share = if (buttons.size > 1) (inner - gap * (buttons.size - 1)) / buttons.size else inner
        val fits = buttons.size > 1 && buttons.all { naturalWidth(it) <= share }
        val now = width to buttons.size
        if (decidedFor != now) {
            decidedFor = now
            sideBySide = fits
        } else if (!fits) {
            sideBySide = false
        }

        var height = 0
        if (buttons.isEmpty()) {
            // all hidden (Sync now during a sync): no gap left above nothing
            setMeasuredDimension(width, 0)
            return
        }
        if (sideBySide) {
            val each = MeasureSpec.makeMeasureSpec(share, MeasureSpec.EXACTLY)
            buttons.forEach { it.measure(each, MeasureSpec.UNSPECIFIED) }
            val tallest = buttons.maxOf { it.measuredHeight }
            // the same height, should one label wrap after all (a font scale change)
            buttons.forEach { it.measure(each, MeasureSpec.makeMeasureSpec(tallest, MeasureSpec.EXACTLY)) }
            height = tallest
        } else {
            val each = MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY)
            buttons.forEach {
                it.measure(each, MeasureSpec.UNSPECIFIED)
                height += it.measuredHeight
            }
        }
        setMeasuredDimension(width, resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec))
    }

    /** The button's width with its label on one line. */
    private fun naturalWidth(button: View): Int {
        button.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        return button.measuredWidth
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val buttons = shown()
        if (sideBySide) {
            // the main act at the end
            var x = paddingLeft
            buttons.asReversed().forEach {
                it.layout(x, paddingTop, x + it.measuredWidth, paddingTop + it.measuredHeight)
                x += it.measuredWidth + gap
            }
        } else {
            var y = paddingTop
            buttons.forEach {
                it.layout(paddingLeft, y, paddingLeft + it.measuredWidth, y + it.measuredHeight)
                y += it.measuredHeight
            }
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?) = LayoutParams(context, attrs)
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p != null
}
