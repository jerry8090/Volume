package com.example.volumemaster.overlay

import android.content.Context
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * LinearLayout that (1) reports every touch so the overlay can restart its auto-collapse timer and
 * (2) turns a horizontal swipe TOWARD the docked edge into a collapse gesture.
 *
 * The swipe is only claimed when the touch did not start in a "no swipe zone" (the slider and the
 * +/- buttons, reported by the page), so dragging the volume slider can never collapse the panel.
 * Everything else (taps on tabs / buttons, vertical movement) still reaches the WebView untouched.
 */
class InteractionLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface SwipeListener {
        fun onSwipeStart()
        /** [dxRaw]: screen-space x distance from where the finger went down (positive = right). */
        fun onSwipeMove(dxRaw: Float)
        fun onSwipeEnd(dxRaw: Float, velocityXRaw: Float)
    }

    var onInteraction: (() -> Unit)? = null
    var swipeListener: SwipeListener? = null

    /** +1 when docked to the right edge (outward = +x), -1 for the left edge. */
    var outwardSign = 1

    /** Panel-relative areas (px) that never start a swipe. */
    var noSwipeZones: List<RectF> = emptyList()

    /** Extra veto (e.g. a slider drag is in progress). */
    var swipeBlocked: () -> Boolean = { false }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var candidate = false
    private var swiping = false
    private var tracker: VelocityTracker? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        onInteraction?.invoke()
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (swipeListener == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                downRawX = ev.rawX
                downRawY = ev.rawY
                candidate = !swipeBlocked() && noSwipeZones.none { it.contains(ev.x, ev.y) }
                if (candidate) {
                    tracker = VelocityTracker.obtain()
                    track(ev)
                }
            }
            MotionEvent.ACTION_MOVE -> if (candidate && !swiping) {
                track(ev)
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                // Clearly horizontal, past touch slop, and heading toward the docked edge.
                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f && dx * outwardSign > 0f) {
                    swiping = true
                    swipeListener?.onSwipeStart()
                    swipeListener?.onSwipeMove(dx)
                    return true // children receive ACTION_CANCEL, we own the rest of the gesture
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!swiping) reset()
        }
        return swiping
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!swiping) return super.onTouchEvent(ev)
        track(ev)
        val dx = ev.rawX - downRawX
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> swipeListener?.onSwipeMove(dx)
            MotionEvent.ACTION_UP -> finish(dx, withVelocity = true)
            MotionEvent.ACTION_CANCEL -> finish(dx, withVelocity = false)
        }
        return true
    }

    /** The WebView asks parents to stay out of touches it handles; our swipe must still be able to win. */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept && candidate && !swiping) return
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private fun finish(dx: Float, withVelocity: Boolean) {
        var vx = 0f
        tracker?.let {
            if (withVelocity) {
                it.computeCurrentVelocity(1000)
                vx = it.xVelocity
            }
        }
        reset()
        swipeListener?.onSwipeEnd(dx, vx)
    }

    /** Velocity in screen coordinates: the panel itself moves while swiping, so view-local x would lie. */
    private fun track(ev: MotionEvent) {
        val t = tracker ?: return
        val adjusted = MotionEvent.obtain(ev)
        adjusted.offsetLocation(ev.rawX - ev.x, ev.rawY - ev.y)
        t.addMovement(adjusted)
        adjusted.recycle()
    }

    private fun reset() {
        tracker?.recycle()
        tracker = null
        candidate = false
        swiping = false
    }
}
