package com.copyeye.app.overlay

import kotlin.math.abs
import kotlin.math.hypot

/** What the raw pointer stream meant. */
sealed interface EyeGesture {
    /** Finger went down; the view should light up but commit to nothing yet. */
    data object Pressed : EyeGesture

    /** Movement crossed the slop. From here the stream can no longer become a tap. */
    data object DragStarted : EyeGesture

    /** Cumulative offset from the position at touch-down. */
    data class DragMoved(val dx: Int, val dy: Int) : EyeGesture

    /** Finger lifted after a drag. */
    data class DragEnded(val dx: Int, val dy: Int) : EyeGesture

    /** A clean tap: no slop crossed, released before the long-press timeout. */
    data object Tap : EyeGesture

    /** Second tap inside the double-tap window. Supersedes the [Tap] that would have followed. */
    data object DoubleTap : EyeGesture

    /** Held past the long-press timeout without moving. */
    data object LongPress : EyeGesture

    /** Gesture abandoned (pointer cancelled, window lost focus). */
    data object Cancelled : EyeGesture
}

/**
 * Turns a pointer stream into [EyeGesture]s.
 *
 * Kept free of `MotionEvent` so the interesting part — deciding whether a touch was a tap or the
 * beginning of a drag — can be tested directly. That distinction is the single most important
 * behaviour in the overlay: getting it wrong means every attempt to move the eye also fires a scan.
 *
 * The caller drives it with [onDown]/[onMove]/[onUp]/[onCancel] and must also call [onTick]
 * periodically (or schedule a single timer at `downTime + longPressTimeoutMs`) so long presses can
 * fire without a pointer event.
 */
class DragGestureHandler(
    private val touchSlopPx: Int,
    private val longPressTimeoutMs: Long = DEFAULT_LONG_PRESS_MS,
    private val doubleTapTimeoutMs: Long = DEFAULT_DOUBLE_TAP_MS,
    private val tapMaxDurationMs: Long = DEFAULT_TAP_MAX_MS,
) {

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var pointerDown = false
    private var dragging = false
    private var longPressFired = false
    private var lastTapUpTime = -1L

    /**
     * A tap is only emitted once the double-tap window has closed, otherwise a double tap would
     * fire a scan *and* a hide. [pendingTapDeadline] is when that window expires.
     */
    private var pendingTapAt = -1L

    val isDragging: Boolean get() = dragging

    val pendingTapDeadline: Long? get() = pendingTapAt.takeIf { it >= 0 }

    fun onDown(x: Float, y: Float, timeMs: Long): List<EyeGesture> {
        downX = x
        downY = y
        downTime = timeMs
        pointerDown = true
        dragging = false
        longPressFired = false
        pendingTapAt = -1L
        return listOf(EyeGesture.Pressed)
    }

    fun onMove(x: Float, y: Float, timeMs: Long): List<EyeGesture> {
        if (!pointerDown) return emptyList()
        val dx = x - downX
        val dy = y - downY
        if (!dragging) {
            if (hypot(dx, dy) < touchSlopPx) return emptyList()
            // Crossing the slop cancels a long press that has not fired yet.
            dragging = true
            return listOf(EyeGesture.DragStarted, EyeGesture.DragMoved(dx.toInt(), dy.toInt()))
        }
        return listOf(EyeGesture.DragMoved(dx.toInt(), dy.toInt()))
    }

    fun onUp(x: Float, y: Float, timeMs: Long): List<EyeGesture> {
        if (!pointerDown) return emptyList()
        pointerDown = false
        val dx = (x - downX).toInt()
        val dy = (y - downY).toInt()

        if (dragging) {
            dragging = false
            return listOf(EyeGesture.DragEnded(dx, dy))
        }
        if (longPressFired) {
            // The long press already consumed this gesture; the release just ends it.
            longPressFired = false
            return emptyList()
        }
        if (timeMs - downTime > tapMaxDurationMs) {
            return listOf(EyeGesture.Cancelled)
        }

        val sinceLastTap = if (lastTapUpTime < 0) Long.MAX_VALUE else timeMs - lastTapUpTime
        return if (sinceLastTap <= doubleTapTimeoutMs) {
            lastTapUpTime = -1L
            pendingTapAt = -1L
            listOf(EyeGesture.DoubleTap)
        } else {
            lastTapUpTime = timeMs
            pendingTapAt = timeMs + doubleTapTimeoutMs
            emptyList()
        }
    }

    fun onCancel(): List<EyeGesture> {
        val wasActive = pointerDown || dragging
        pointerDown = false
        dragging = false
        longPressFired = false
        pendingTapAt = -1L
        return if (wasActive) listOf(EyeGesture.Cancelled) else emptyList()
    }

    /**
     * Advances time-based gestures. Call from a scheduled runnable, not a poll loop — the two
     * deadlines that matter are exposed as [pendingTapDeadline] and `downTime + longPressTimeoutMs`.
     */
    fun onTick(timeMs: Long): List<EyeGesture> {
        val out = mutableListOf<EyeGesture>()
        if (pointerDown && !dragging && !longPressFired && timeMs - downTime >= longPressTimeoutMs) {
            longPressFired = true
            out += EyeGesture.LongPress
        }
        if (pendingTapAt >= 0 && timeMs >= pendingTapAt) {
            pendingTapAt = -1L
            lastTapUpTime = -1L
            out += EyeGesture.Tap
        }
        return out
    }

    /** True when the current stream can still turn into a tap. */
    fun couldStillBeTap(x: Float, y: Float): Boolean =
        pointerDown && !dragging &&
            abs(x - downX) < touchSlopPx && abs(y - downY) < touchSlopPx

    companion object {
        const val DEFAULT_LONG_PRESS_MS = 420L
        const val DEFAULT_DOUBLE_TAP_MS = 220L
        const val DEFAULT_TAP_MAX_MS = 900L
    }
}
