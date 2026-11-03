package com.mickstarify.zooforzotero.LibraryActivity

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * Pull the list down from its top to sync, without a spinner of its own: [onPull] reports
 * how far (1 = far enough), and the toolbar's sync icon shows it, then the sync itself
 * (LibraryActivity.showPull). Replaces SwipeRefreshLayout, whose round spinner showed the
 * same sync a second time.
 *
 * It watches the finger without taking it from the list: the pull is how far the finger
 * has gone down since the list reached its top. Not nested scrolling: from Android 12
 * RecyclerView feeds a drag into its overscroll stretch and passes on none of it, so a
 * second pull while the stretch eases back went unseen. The list still gets every move,
 * so its glow or stretch shows. A child that doesn't scroll (the empty list's message) is
 * taken over once the pull starts, so its button doesn't also get the tap.
 */
class PullToSyncLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** How far the pull is, 1 = far enough to sync; 0 when it ends. */
    var onPull: (Float) -> Unit = {}
    /** The pull ended; [sync] = it went far enough. */
    var onRelease: (sync: Boolean) -> Unit = {}
    /** Whether the list can scroll up, so a downward drag scrolls it instead of pulling. */
    var canChildScrollUp: () -> Boolean = { false }
    /** Whether a child scrolls at all: one that doesn't is taken over once the pull starts. */
    var childScrolls: () -> Boolean = { true }

    // finger travel that starts a sync: SwipeRefreshLayout's 64dp, which it halved, doubled
    private val distance = 128 * resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var pulled = 0f
    private var far = false
    // the finger's y where the pull is 0: where it went down, or the list reached its top,
    // or the highest point since
    private var anchorY = 0f
    private var tracking = false

    private fun pullTo(px: Float) {
        if (px == pulled) return
        pulled = px
        val nowFar = pulled >= distance
        // the moment letting go would sync, felt as well as seen
        if (nowFar && !far) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        far = nowFar
        onPull(pulled / distance)
    }

    private fun release(sync: Boolean) {
        tracking = false
        val wasPulling = pulled > 0f
        pulled = 0f
        far = false
        if (wasPulling) {
            onPull(0f)
            onRelease(sync)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) release(false)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = isEnabled
                anchorY = ev.y
            }
            // a second finger: zooming or a stray touch, not a pull
            MotionEvent.ACTION_POINTER_DOWN -> release(false)
            MotionEvent.ACTION_MOVE -> if (tracking) {
                if (canChildScrollUp() || ev.y < anchorY) anchorY = ev.y
                // past the touch slop, so a tap or a sideways swipe doesn't start one
                pullTo((ev.y - anchorY - touchSlop).coerceAtLeast(0f))
            }
            MotionEvent.ACTION_UP -> release(far && isEnabled)
            MotionEvent.ACTION_CANCEL -> release(false)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = pulled > 0f && !childScrolls()

    // Taken over from a child that doesn't scroll; dispatchTouchEvent follows the pull. The
    // down is claimed when nothing under the finger wants it, else the rest never comes here.
    override fun onTouchEvent(ev: MotionEvent): Boolean =
        pulled > 0f || (ev.actionMasked == MotionEvent.ACTION_DOWN && !childScrolls()) || super.onTouchEvent(ev)
}
