package com.copyeye.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import com.copyeye.app.core.common.ApiLevel
import com.copyeye.app.core.common.Hap
import com.copyeye.app.core.common.Haptics
import com.copyeye.app.core.state.CopyEyeState
import com.copyeye.app.core.state.CopyEyeStateMachine
import com.copyeye.app.data.preferences.AppSettings
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything that happens in the overlay window: drawing Iris, moving her, dimming her, and
 * deciding what a touch meant.
 *
 * Deliberately separate from [FloatingEyeService]. The service's job is process lifecycle —
 * foreground notification, projection session, Android's rules about when a service may exist. The
 * window's job is interaction. Mixing them produces a class where a notification-channel change
 * and a drag calculation live twenty lines apart, and it makes the interaction untestable.
 */
class FloatingEyeController(
    private val context: Context,
    private val haptics: Haptics,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onScanRequested()
        fun onScanAreaRequested()
        fun onOpenHistory()
        fun onOpenSettings()
        fun onPauseRequested()
        fun onHideForAnHour()
        fun onStopRequested()
        fun onStateChanged(state: CopyEyeState)
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val boundsProvider = OverlayBoundsProvider(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val eyeView = IrisEyeView(context)
    private var removeTarget: RemoveTargetView? = null
    private var quickMenu: QuickMenuView? = null

    private val eyeParams = buildEyeParams()
    private var attached = false

    private var settings: AppSettings = AppSettings()
    private var eyeSizePx = 0
    private var bounds = OverlayBounds(0, 0, 0, 0)
    private var placement = EyePlacement(0, 0, SnapEdge.Right)

    private var state: CopyEyeState = CopyEyeState.Ready
    private var dimmed = false
    private var isPeeked = false

    private var moveAnimator: ValueAnimator? = null
    private var alphaAnimator: ValueAnimator? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val gestures = DragGestureHandler(touchSlop)

    private var dragStartX = 0
    private var dragStartY = 0

    private val dimRunnable = Runnable { applyDim(true) }
    private val gestureTickRunnable = object : Runnable {
        override fun run() {
            val events = gestures.onTick(SystemClock.uptimeMillis())
            events.forEach(::handleGesture)
            if (gestures.pendingTapDeadline != null || gestures.isDragging) {
                mainHandler.postDelayed(this, GESTURE_TICK_MS)
            }
        }
    }

    val currentState: CopyEyeState get() = state

    // --- Lifecycle -----------------------------------------------------------------------------

    fun attach(settings: AppSettings) {
        if (attached) {
            updateSettings(settings)
            return
        }
        this.settings = settings
        applySettingsToView()
        eyeSizePx = boundsProvider.dpToPx(settings.eyeSizeDp)
        eyeParams.width = eyeSizePx
        eyeParams.height = eyeSizePx

        bounds = boundsProvider.currentBounds(null, eyeSizePx)
        placement = EdgeSnapController.fromFractions(
            settings.eyePositionXFraction,
            settings.eyePositionYFraction,
            eyeSizePx,
            bounds,
            settings.preferredEdge,
        )
        eyeParams.x = placement.x
        eyeParams.y = placement.y

        eyeView.setOnTouchListener(::onEyeTouch)
        // A TalkBack or switch-access activation bypasses the touch stream entirely.
        eyeView.onAccessibilityScan = {
            if (!state.isScanInFlight) {
                wake()
                callbacks.onScanRequested()
                scheduleDim()
            }
        }

        try {
            windowManager.addView(eyeView, eyeParams)
            attached = true
        } catch (e: WindowManager.BadTokenException) {
            // Overlay permission was revoked between the service starting and this call.
            Log.e(TAG, "Overlay refused: ${e.message}")
            transitionTo(CopyEyeState.PermissionRequired)
            return
        } catch (e: SecurityException) {
            Log.e(TAG, "Overlay not permitted")
            transitionTo(CopyEyeState.PermissionRequired)
            return
        }

        // Insets are only readable once the view is attached, so the bounds are recomputed here.
        eyeView.post {
            recomputeBounds(animate = false)
            scheduleDim()
        }
        transitionTo(CopyEyeState.EyeIdle)
    }

    fun detach() {
        mainHandler.removeCallbacksAndMessages(null)
        moveAnimator?.cancel()
        alphaAnimator?.cancel()
        dismissQuickMenu()
        hideRemoveTarget()
        if (attached) {
            runCatching { windowManager.removeView(eyeView) }
            attached = false
        }
        transitionTo(CopyEyeState.Disabled)
    }

    fun updateSettings(newSettings: AppSettings) {
        val sizeChanged = newSettings.eyeSizeDp != settings.eyeSizeDp
        settings = newSettings
        haptics.enabled = newSettings.hapticsEnabled
        applySettingsToView()
        if (sizeChanged && attached) {
            eyeSizePx = boundsProvider.dpToPx(newSettings.eyeSizeDp)
            eyeParams.width = eyeSizePx
            eyeParams.height = eyeSizePx
            recomputeBounds(animate = false)
        }
        scheduleDim()
    }

    /** Hides the eye without stopping the service, so a scan can happen unobstructed. */
    fun setEyeVisible(visible: Boolean) {
        if (!attached) return
        eyeView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Hides the eye and waits until that hide has actually reached the screen.
     *
     * Setting `visibility` is synchronous, but the compositor is not: the window keeps being drawn
     * until the next frame is composed. Grabbing a capture immediately after hiding therefore
     * catches Iris inside her own screenshot — visibly, over the text the user is trying to read.
     *
     * Two frames rather than one, because the first only guarantees the hide was *submitted*.
     * At 60 Hz this costs about 33 ms; the timeout stops a stalled compositor from blocking a scan
     * forever.
     */
    suspend fun hideEyeForCapture() {
        if (!attached || eyeView.visibility == View.GONE) return
        eyeView.visibility = View.GONE
        withTimeoutOrNull(COMPOSITE_WAIT_TIMEOUT_MS) {
            repeat(FRAMES_TO_AWAIT) { awaitFrame() }
        }
    }

    private suspend fun awaitFrame() = suspendCancellableCoroutine { continuation ->
        val callback = Choreographer.FrameCallback {
            if (continuation.isActive) continuation.resume(Unit)
        }
        Choreographer.getInstance().postFrameCallback(callback)
        continuation.invokeOnCancellation {
            Choreographer.getInstance().removeFrameCallback(callback)
        }
    }

    fun setMood(mood: IrisEyeView.Mood) {
        eyeView.mood = mood
    }

    /** Plays the copy-succeeded flourish: one blink and a checkmark in the pupil. */
    fun showSuccess() {
        eyeView.mood = IrisEyeView.Mood.Success
        haptics.play(Hap.Success)
        mainHandler.postDelayed({ eyeView.mood = IrisEyeView.Mood.Idle }, SUCCESS_HOLD_MS)
    }

    fun showBlocked() {
        eyeView.mood = IrisEyeView.Mood.Blocked
        haptics.play(Hap.Rejected)
        mainHandler.postDelayed({ eyeView.mood = IrisEyeView.Mood.Idle }, BLOCKED_HOLD_MS)
    }

    fun onConfigurationChanged(configuration: Configuration) {
        if (!attached) return
        // The window has not been re-laid-out yet at this point, so let it settle first.
        eyeView.post { recomputeBounds(animate = true) }
    }

    fun transitionTo(target: CopyEyeState) {
        val next = CopyEyeStateMachine.transition(state, target)
        if (next == state) return
        state = next
        callbacks.onStateChanged(next)
    }

    // --- Settings → view -----------------------------------------------------------------------

    private fun applySettingsToView() {
        eyeView.style = settings.eyeStyle
        eyeView.accent = settings.eyeAccent
        eyeView.blinkIntervalSeconds = settings.blinkIntervalSeconds
        eyeView.animationIntensity =
            if (settings.reducedMotion) {
                com.copyeye.app.data.preferences.AnimationIntensity.Off
            } else {
                settings.animationIntensity
            }
        eyeView.alpha = if (dimmed) settings.effectiveIdleOpacity else 1f
    }

    // --- Geometry ------------------------------------------------------------------------------

    private fun recomputeBounds(animate: Boolean) {
        if (!attached) return
        val previousBounds = bounds
        val newBounds = boundsProvider.currentBounds(eyeView, eyeSizePx)
        if (newBounds == previousBounds) return

        val remapped = EdgeSnapController.remap(
            placement,
            eyeSizePx,
            previousBounds.takeIf { it.width > 0 } ?: newBounds,
            newBounds,
            settings.preferredEdge,
        )
        bounds = newBounds
        moveTo(remapped, animate)
        applyObstructionAvoidance()
    }

    /** Lifts the eye clear of the keyboard when one opens under it. */
    private fun applyObstructionAvoidance() {
        if (!settings.autoRepositionEnabled) return
        val imeHeight = boundsProvider.bottomObstruction(eyeView)
        if (imeHeight <= 0) return
        val obstructedTop = bounds.bottom - imeHeight
        val adjusted = EdgeSnapController.avoidObstruction(
            placement,
            eyeSizePx,
            bounds,
            obstructedTop,
            boundsProvider.dpToPx(OBSTRUCTION_GAP_DP),
        )
        if (adjusted != placement) moveTo(adjusted, animate = true)
    }

    private fun moveTo(target: EyePlacement, animate: Boolean) {
        placement = target
        val targetX = target.x + if (isPeeked) {
            EdgeSnapController.peekOffsetFor(target.edge, eyeSizePx, settings.edgePeekFraction)
        } else {
            0
        }
        if (!animate || settings.motionSuppressed) {
            eyeParams.x = targetX
            eyeParams.y = target.y
            safeUpdateViewLayout()
            return
        }
        moveAnimator?.cancel()
        val fromX = eyeParams.x
        val fromY = eyeParams.y
        moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_DURATION_MS
            // Overshoot reads as a spring without pulling in the physics library.
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { animation ->
                val t = animation.animatedValue as Float
                eyeParams.x = (fromX + (targetX - fromX) * t).toInt()
                eyeParams.y = (fromY + (target.y - fromY) * t).toInt()
                safeUpdateViewLayout()
            }
            start()
        }
    }

    private fun safeUpdateViewLayout() {
        if (!attached) return
        try {
            windowManager.updateViewLayout(eyeView, eyeParams)
        } catch (e: IllegalArgumentException) {
            // The view was removed underneath us; nothing left to update.
            attached = false
        }
    }

    // --- Dimming -------------------------------------------------------------------------------

    private fun scheduleDim() {
        mainHandler.removeCallbacks(dimRunnable)
        if (!settings.autoHideEnabled) {
            applyDim(false)
            return
        }
        mainHandler.postDelayed(dimRunnable, settings.autoDimDelayMs)
    }

    private fun applyDim(dim: Boolean) {
        if (dimmed == dim) return
        dimmed = dim
        isPeeked = dim && settings.edgePeekFraction > 0f

        alphaAnimator?.cancel()
        val targetAlpha = if (dim) settings.effectiveIdleOpacity else 1f
        if (settings.motionSuppressed) {
            eyeView.alpha = targetAlpha
        } else {
            alphaAnimator = ValueAnimator.ofFloat(eyeView.alpha, targetAlpha).apply {
                duration = if (dim) DIM_FADE_MS else WAKE_FADE_MS
                addUpdateListener { eyeView.alpha = it.animatedValue as Float }
                start()
            }
        }

        // Dimming also tucks the eye partly behind its edge and stops idle motion.
        eyeView.animationIntensity = when {
            settings.reducedMotion -> com.copyeye.app.data.preferences.AnimationIntensity.Off
            dim -> com.copyeye.app.data.preferences.AnimationIntensity.Subtle
            else -> settings.animationIntensity
        }
        moveTo(placement, animate = true)
        transitionTo(if (dim) CopyEyeState.EyeDimmed else CopyEyeState.EyeIdle)
    }

    private fun wake() {
        mainHandler.removeCallbacks(dimRunnable)
        applyDim(false)
    }

    // --- Touch ---------------------------------------------------------------------------------

    @Suppress("ClickableViewAccessibility")
    private fun onEyeTouch(view: View, event: MotionEvent): Boolean {
        val rawX = event.rawX
        val rawY = event.rawY
        val now = SystemClock.uptimeMillis()

        val events = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wake()
                dragStartX = eyeParams.x
                dragStartY = eyeParams.y
                mainHandler.post(gestureTickRunnable)
                gestures.onDown(rawX, rawY, now)
            }
            MotionEvent.ACTION_MOVE -> gestures.onMove(rawX, rawY, now)
            MotionEvent.ACTION_UP -> gestures.onUp(rawX, rawY, now)
            MotionEvent.ACTION_CANCEL -> gestures.onCancel()
            else -> emptyList()
        }
        events.forEach(::handleGesture)

        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            // Keep ticking briefly so a pending tap can still resolve into a double tap.
            mainHandler.postDelayed(gestureTickRunnable, GESTURE_TICK_MS)
        }
        return true
    }

    private fun handleGesture(gesture: EyeGesture) {
        when (gesture) {
            EyeGesture.Pressed -> {
                eyeView.setPressed(true, animate = !settings.motionSuppressed)
            }

            EyeGesture.DragStarted -> {
                transitionTo(CopyEyeState.Dragging)
                moveAnimator?.cancel()
                dismissQuickMenu()
                showRemoveTarget()
            }

            is EyeGesture.DragMoved -> {
                val clamped = EdgeSnapController.clamp(
                    dragStartX + gesture.dx,
                    dragStartY + gesture.dy,
                    eyeSizePx,
                    bounds,
                )
                eyeParams.x = clamped.x
                eyeParams.y = clamped.y
                placement = clamped
                safeUpdateViewLayout()
                updateRemoveTargetArming()
            }

            is EyeGesture.DragEnded -> {
                eyeView.setPressed(false, animate = !settings.motionSuppressed)
                if (isOverRemoveTarget()) {
                    hideRemoveTarget()
                    callbacks.onStopRequested()
                    return
                }
                hideRemoveTarget()
                val snapped = EdgeSnapController.snap(
                    eyeParams.x,
                    eyeParams.y,
                    eyeSizePx,
                    bounds,
                    settings.preferredEdge,
                )
                moveTo(snapped, animate = true)
                transitionTo(CopyEyeState.EyeIdle)
                persistPosition(snapped)
                scheduleDim()
            }

            EyeGesture.Tap -> {
                eyeView.setPressed(false, animate = !settings.motionSuppressed)
                if (state.isScanInFlight) return
                haptics.play(Hap.ScanStart)
                callbacks.onScanRequested()
                scheduleDim()
            }

            EyeGesture.DoubleTap -> {
                eyeView.setPressed(false, animate = !settings.motionSuppressed)
                callbacks.onHideForAnHour()
            }

            EyeGesture.LongPress -> {
                haptics.play(Hap.TextReady)
                showQuickMenu()
            }

            EyeGesture.Cancelled -> {
                eyeView.setPressed(false, animate = !settings.motionSuppressed)
                hideRemoveTarget()
                transitionTo(CopyEyeState.EyeIdle)
                scheduleDim()
            }
        }
    }

    private var onPositionPersisted: ((Float, Float) -> Unit)? = null

    fun setPositionPersister(persister: (Float, Float) -> Unit) {
        onPositionPersisted = persister
    }

    private fun persistPosition(snapped: EyePlacement) {
        val (x, y) = EdgeSnapController.toFractions(snapped, eyeSizePx, bounds)
        onPositionPersisted?.invoke(x, y)
    }

    // --- Remove target -------------------------------------------------------------------------

    private fun showRemoveTarget() {
        if (removeTarget != null) return
        val size = boundsProvider.dpToPx(RemoveTargetView.SIZE_DP)
        val view = RemoveTargetView(context)
        val params = buildOverlayParams(size, size, focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bounds.left + bounds.width / 2) - size / 2
            y = bounds.bottom - size - boundsProvider.dpToPx(REMOVE_TARGET_BOTTOM_DP)
        }
        try {
            windowManager.addView(view, params)
            removeTarget = view
            removeTargetParams = params
            if (!settings.motionSuppressed) {
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(140L).start()
            }
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Remove target refused")
        }
    }

    private var removeTargetParams: WindowManager.LayoutParams? = null

    private fun updateRemoveTargetArming() {
        val target = removeTarget ?: return
        target.isArmed = isOverRemoveTarget()
    }

    private fun isOverRemoveTarget(): Boolean {
        val target = removeTarget ?: return false
        val params = removeTargetParams ?: return false
        val eyeCentreX = eyeParams.x + eyeSizePx / 2f
        val eyeCentreY = eyeParams.y + eyeSizePx / 2f
        return target.capturesPoint(eyeCentreX, eyeCentreY, params.x, params.y)
    }

    private fun hideRemoveTarget() {
        val view = removeTarget ?: return
        removeTarget = null
        removeTargetParams = null
        runCatching { windowManager.removeView(view) }
    }

    // --- Quick menu ----------------------------------------------------------------------------

    private fun showQuickMenu() {
        if (quickMenu != null) return
        transitionTo(CopyEyeState.OpeningQuickMenu)
        val menu = QuickMenuView(context) { action ->
            dismissQuickMenu()
            when (action) {
                QuickAction.ScanScreen -> callbacks.onScanRequested()
                QuickAction.ScanArea -> callbacks.onScanAreaRequested()
                QuickAction.History -> callbacks.onOpenHistory()
                QuickAction.Settings -> callbacks.onOpenSettings()
                QuickAction.Pause -> callbacks.onPauseRequested()
                QuickAction.HideForAnHour -> callbacks.onHideForAnHour()
                QuickAction.StopService -> callbacks.onStopRequested()
            }
        }
        // Focusable so a tap outside can dismiss it, unlike the eye itself.
        val params = buildOverlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            focusable = true,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val menuWidthGuess = boundsProvider.dpToPx(QUICK_MENU_WIDTH_DP)
            x = if (placement.edge == SnapEdge.Left) {
                placement.x + eyeSizePx + boundsProvider.dpToPx(8)
            } else {
                (placement.x - menuWidthGuess - boundsProvider.dpToPx(8)).coerceAtLeast(bounds.left)
            }
            y = placement.y.coerceAtMost(bounds.bottom - boundsProvider.dpToPx(QUICK_MENU_HEIGHT_DP))
                .coerceAtLeast(bounds.top)
        }
        menu.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismissQuickMenu()
                true
            } else {
                false
            }
        }
        try {
            windowManager.addView(menu, params)
            quickMenu = menu
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Quick menu refused")
            transitionTo(CopyEyeState.EyeIdle)
        }
    }

    fun dismissQuickMenu() {
        val menu = quickMenu ?: return
        quickMenu = null
        runCatching { windowManager.removeView(menu) }
        if (state == CopyEyeState.OpeningQuickMenu) transitionTo(CopyEyeState.EyeIdle)
        scheduleDim()
    }

    // --- Window params -------------------------------------------------------------------------

    private fun buildEyeParams(): WindowManager.LayoutParams =
        buildOverlayParams(0, 0, focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    private fun buildOverlayParams(
        width: Int,
        height: Int,
        focusable: Boolean,
    ): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        flags = if (focusable) {
            flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Lets the eye sit beside a notch rather than being pushed into the letterbox below it.
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private companion object {
        const val TAG = "CopyEye/Overlay"
        const val SNAP_DURATION_MS = 260L
        const val DIM_FADE_MS = 420L
        const val WAKE_FADE_MS = 110L
        const val SUCCESS_HOLD_MS = 900L
        const val BLOCKED_HOLD_MS = 1_200L
        const val GESTURE_TICK_MS = 40L
        const val OBSTRUCTION_GAP_DP = 12
        const val REMOVE_TARGET_BOTTOM_DP = 48
        const val QUICK_MENU_WIDTH_DP = 210
        const val QUICK_MENU_HEIGHT_DP = 340
        const val FRAMES_TO_AWAIT = 2
        const val COMPOSITE_WAIT_TIMEOUT_MS = 250L
    }
}
