package com.copyeye.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * The "drop here to stop CopyEye" target that appears at the bottom of the screen during a drag.
 *
 * Sized generously: this is a one-handed gesture aimed at the bottom of the screen while the user
 * is looking at something else, so the capture radius is deliberately larger than the drawn circle.
 */
class RemoveTargetView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    /** True while the eye is close enough that releasing would stop the service. */
    var isArmed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            rebuildGlow()
            invalidate()
        }

    init {
        contentDescription = "Stop CopyEye"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGlow()
    }

    /**
     * The glow depends only on size and armed state, both of which change far less often than the
     * view draws. Building it here keeps allocation out of `onDraw`, which runs on every frame of
     * the drag.
     */
    private fun rebuildGlow() {
        if (width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = currentRadius()
        glowPaint.shader = RadialGradient(
            cx, cy, radius * 1.9f,
            intArrayOf(if (isArmed) 0x66E05B5B else 0x33000000, Color.TRANSPARENT),
            floatArrayOf(0.35f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun currentRadius(): Float =
        min(width, height) / 2f * if (isArmed) 0.92f else 0.72f

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = currentRadius()

        canvas.drawCircle(cx, cy, radius * 1.9f, glowPaint)

        circlePaint.color = if (isArmed) 0xFFE05B5B.toInt() else 0xCC2A2D38.toInt()
        canvas.drawCircle(cx, cy, radius, circlePaint)

        crossPaint.strokeWidth = 2.4f * density
        val arm = radius * 0.36f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, crossPaint)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, crossPaint)
    }

    /** True when a point in the same window coordinate space is inside the capture radius. */
    fun capturesPoint(x: Float, y: Float, selfX: Int, selfY: Int): Boolean {
        val cx = selfX + width / 2f
        val cy = selfY + height / 2f
        val captureRadius = min(width, height) / 2f * CAPTURE_RADIUS_MULTIPLIER
        return hypot(x - cx, y - cy) <= captureRadius
    }

    companion object {
        const val SIZE_DP = 72

        /** The target catches from noticeably further out than it looks. */
        private const val CAPTURE_RADIUS_MULTIPLIER = 1.35f
    }
}
