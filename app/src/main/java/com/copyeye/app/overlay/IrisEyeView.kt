package com.copyeye.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.copyeye.app.R
import com.copyeye.app.data.preferences.AnimationIntensity
import com.copyeye.app.data.preferences.EyeAccent
import com.copyeye.app.data.preferences.EyeStyle
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Iris, drawn entirely on a [Canvas].
 *
 * Vector drawing beats Lottie or Rive here for one reason: this view sits on screen for hours while
 * the user does something else, so its idle cost has to be as close to zero as an on-screen view
 * can get. Between blinks nothing is scheduled at all — no animator, no frame callback. A blink
 * wakes a short [ValueAnimator], then everything goes quiet again.
 */
class IrisEyeView(context: Context) : View(context) {

    /** What Iris is doing, which changes what gets drawn on top of the eye. */
    enum class Mood { Idle, Pressed, Scanning, Success, Blocked }

    private val density = resources.displayMetrics.density

    private val scleraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val irisPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF10121A.toInt() }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33000000
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val arcRect = RectF()

    var style: EyeStyle = EyeStyle.Classic
        set(value) { field = value; invalidate() }

    var accent: EyeAccent = EyeAccent.Violet
        set(value) { field = value; rebuildShaders(); invalidate() }

    var animationIntensity: AnimationIntensity = AnimationIntensity.Subtle
        set(value) {
            field = value
            if (value == AnimationIntensity.Off) stopIdleMotion() else scheduleBlink()
        }

    /** Mean seconds between blinks; the actual interval is jittered around it. */
    var blinkIntervalSeconds: Int = 6

    var mood: Mood = Mood.Idle
        set(value) {
            if (field == value) return
            field = value
            when (value) {
                Mood.Scanning -> startScanSpinner()
                Mood.Success -> { stopScanSpinner(); blinkNow() }
                else -> stopScanSpinner()
            }
            invalidate()
        }

    // --- Animated quantities -------------------------------------------------------------------

    /** 1f = fully open, 0f = shut. */
    private var openness = 1f
    private var gazeX = 0f
    private var gazeY = 0f
    private var breathe = 0f
    private var spinner = 0f
    private var pressScale = 1f

    private var blinkAnimator: ValueAnimator? = null
    private var gazeAnimator: ValueAnimator? = null
    private var breatheAnimator: ValueAnimator? = null
    private var spinnerAnimator: ValueAnimator? = null

    private val random = Random(System.nanoTime())

    private val blinkRunnable = Runnable {
        if (isAttachedToWindow) {
            blinkNow()
            maybeGlance()
            scheduleBlink()
        }
    }

    init {
        // Software layer: the drawing is a handful of circles, and a hardware layer for a 42dp view
        // costs more in texture uploads than it saves.
        setLayerType(LAYER_TYPE_NONE, null)
        contentDescription = context.getString(R.string.a11y_floating_eye)
        isFocusable = true
        isClickable = true
        rebuildShaders()
    }

    /**
     * The touch listener that drives gestures consumes every event, so TalkBack's synthesised click
     * would otherwise reach nothing. Routing it here gives switch and screen-reader users the same
     * "tap to scan" the gesture handler gives everyone else.
     */
    override fun performClick(): Boolean {
        super.performClick()
        onAccessibilityScan?.invoke()
        return true
    }

    /** Invoked when an accessibility service performs a click on Iris. */
    var onAccessibilityScan: (() -> Unit)? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleBlink()
        startBreathing()
    }

    override fun onDetachedFromWindow() {
        stopIdleMotion()
        stopScanSpinner()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    /** Immediate visual response to touch-down, before any gesture has been classified. */
    fun setPressed(pressed: Boolean, animate: Boolean) {
        val target = if (pressed) 1.12f else 1f
        if (!animate || animationIntensity == AnimationIntensity.Off) {
            pressScale = target
            invalidate()
            return
        }
        ValueAnimator.ofFloat(pressScale, target).apply {
            duration = 110L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { pressScale = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun blinkNow() {
        if (animationIntensity == AnimationIntensity.Off) return
        blinkAnimator?.cancel()
        blinkAnimator = ValueAnimator.ofFloat(1f, 0.06f, 1f).apply {
            duration = 190L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { openness = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun scheduleBlink() {
        removeCallbacks(blinkRunnable)
        if (animationIntensity == AnimationIntensity.Off) return
        val base = blinkIntervalSeconds.coerceIn(3, 20) * 1000L
        val jitter = random.nextLong(-base / 3, base / 3 + 1)
        postDelayed(blinkRunnable, (base + jitter).coerceAtLeast(1_500L))
    }

    /** Occasionally look toward the middle of the screen, which reads as attentiveness. */
    private fun maybeGlance() {
        if (animationIntensity != AnimationIntensity.Full) return
        if (random.nextFloat() > 0.45f) return
        val angle = random.nextFloat() * 2f * Math.PI.toFloat()
        val targetX = cos(angle) * 0.22f
        val targetY = sin(angle) * 0.16f
        gazeAnimator?.cancel()
        gazeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 480L
            interpolator = AccelerateDecelerateInterpolator()
            val fromX = gazeX
            val fromY = gazeY
            addUpdateListener {
                val t = it.animatedValue as Float
                // Out and back, so the pupil always returns to centre.
                val ease = if (t < 0.5f) t * 2f else (1f - t) * 2f
                gazeX = fromX + (targetX - fromX) * ease
                gazeY = fromY + (targetY - fromY) * ease
                invalidate()
            }
            start()
        }
    }

    private fun startBreathing() {
        if (animationIntensity != AnimationIntensity.Full) return
        breatheAnimator?.cancel()
        breatheAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3_400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { breathe = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun startScanSpinner() {
        spinnerAnimator?.cancel()
        if (animationIntensity == AnimationIntensity.Off) return
        spinnerAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = null
            addUpdateListener { spinner = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopScanSpinner() {
        spinnerAnimator?.cancel()
        spinnerAnimator = null
        spinner = 0f
    }

    private fun stopIdleMotion() {
        removeCallbacks(blinkRunnable)
        blinkAnimator?.cancel(); blinkAnimator = null
        gazeAnimator?.cancel(); gazeAnimator = null
        breatheAnimator?.cancel(); breatheAnimator = null
        openness = 1f
        gazeX = 0f; gazeY = 0f; breathe = 0f
    }

    // --- Drawing -------------------------------------------------------------------------------

    private fun accentColors(): Pair<Int, Int> = when (accent) {
        EyeAccent.Violet -> 0xFF8B7CF6.toInt() to 0xFF5B49D6.toInt()
        EyeAccent.Cyan -> 0xFF4CC7E8.toInt() to 0xFF1E7FA8.toInt()
        EyeAccent.Rose -> 0xFFF08BB4.toInt() to 0xFFC24C82.toInt()
        EyeAccent.Amber -> 0xFFF3B04C.toInt() to 0xFFC07A18.toInt()
        EyeAccent.Mono -> 0xFF9AA3B2.toInt() to 0xFF4B5262.toInt()
    }

    private fun rebuildShaders() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f

        // The sclera is a very slightly warm off-white so it stays visible on a white background.
        scleraPaint.shader = RadialGradient(
            cx, cy - r * 0.18f, r,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFEFF1F6.toInt(), 0xFFD9DDE6.toInt()),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )

        val (light, dark) = accentColors()
        irisPaint.shader = RadialGradient(
            cx, cy, r * 0.46f,
            intArrayOf(light, dark),
            floatArrayOf(0.15f, 1f),
            Shader.TileMode.CLAMP,
        )

        glowPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(light and 0x40FFFFFF, Color.TRANSPARENT),
            floatArrayOf(0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val breatheOffset = if (animationIntensity == AnimationIntensity.Full) {
            (breathe - 0.5f) * 2f * density
        } else {
            0f
        }
        val cy = h / 2f + breatheOffset
        // Leave room for the glow and the scanning arc.
        val radius = min(w, h) / 2f * 0.82f * pressScale

        canvas.drawCircle(cx, cy, radius * 1.18f, glowPaint)

        val save = canvas.save()
        // The blink is a shutter: clipping the eye to a shrinking horizontal band reads as an
        // eyelid closing without needing a second lid drawable that would have to match the theme.
        val bandHalf = (radius * openness).coerceAtLeast(0.5f)
        canvas.clipRect(cx - radius, cy - bandHalf, cx + radius, cy + bandHalf)

        when (style) {
            EyeStyle.Classic -> drawClassic(canvas, cx, cy, radius)
            EyeStyle.Minimal -> drawMinimal(canvas, cx, cy, radius)
            EyeStyle.Ring -> drawRing(canvas, cx, cy, radius)
        }
        canvas.restoreToCount(save)

        if (mood == Mood.Scanning) drawScanArc(canvas, cx, cy, radius)
        if (mood == Mood.Success) drawCheck(canvas, cx, cy, radius)
        if (mood == Mood.Blocked) drawBlocked(canvas, cx, cy, radius)
    }

    private fun drawClassic(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, scleraPaint)
        rimPaint.strokeWidth = 1f * density
        canvas.drawCircle(cx, cy, radius - rimPaint.strokeWidth / 2f, rimPaint)

        val irisR = radius * 0.52f
        val ix = cx + gazeX * radius * 0.5f
        val iy = cy + gazeY * radius * 0.5f
        canvas.drawCircle(ix, iy, irisR, irisPaint)
        canvas.drawCircle(ix, iy, irisR * 0.44f, pupilPaint)
        canvas.drawCircle(ix - irisR * 0.34f, iy - irisR * 0.36f, irisR * 0.20f, glintPaint)
    }

    private fun drawMinimal(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val (light, dark) = accentColors()
        arcPaint.shader = null
        arcPaint.color = dark
        arcPaint.strokeWidth = radius * 0.16f
        arcPaint.style = Paint.Style.STROKE
        canvas.drawCircle(cx, cy, radius * 0.86f, arcPaint)
        val px = cx + gazeX * radius * 0.4f
        val py = cy + gazeY * radius * 0.4f
        pupilPaint.color = light
        canvas.drawCircle(px, py, radius * 0.3f, pupilPaint)
        pupilPaint.color = 0xFF10121A.toInt()
    }

    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, scleraPaint)
        val (light, _) = accentColors()
        arcPaint.shader = null
        arcPaint.color = light
        arcPaint.strokeWidth = radius * 0.22f
        canvas.drawCircle(cx, cy, radius * 0.66f, arcPaint)
        canvas.drawCircle(
            cx + gazeX * radius * 0.3f,
            cy + gazeY * radius * 0.3f,
            radius * 0.22f,
            pupilPaint,
        )
    }

    private fun drawScanArc(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val (light, _) = accentColors()
        arcPaint.shader = null
        arcPaint.color = light
        arcPaint.strokeWidth = 2.2f * density
        arcRect.set(
            cx - radius * 1.16f, cy - radius * 1.16f,
            cx + radius * 1.16f, cy + radius * 1.16f,
        )
        canvas.drawArc(arcRect, spinner, 96f, false, arcPaint)
        canvas.drawArc(arcRect, spinner + 180f, 52f, false, arcPaint)
    }

    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        checkPaint.strokeWidth = 2.4f * density
        val s = radius * 0.42f
        canvas.drawLine(cx - s, cy, cx - s * 0.25f, cy + s * 0.7f, checkPaint)
        canvas.drawLine(cx - s * 0.25f, cy + s * 0.7f, cx + s, cy - s * 0.6f, checkPaint)
    }

    private fun drawBlocked(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        arcPaint.shader = null
        arcPaint.color = 0xFFE05B5B.toInt()
        arcPaint.strokeWidth = 2.4f * density
        val s = radius * 0.62f
        canvas.drawLine(cx - s, cy - s, cx + s, cy + s, arcPaint)
    }

}
