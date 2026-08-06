package com.copyeye.app.overlay

import android.content.Context
import android.graphics.Point
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import com.copyeye.app.core.common.ApiLevel

/**
 * Works out where on screen the eye is allowed to sit.
 *
 * The usable area is the display minus everything that would either hide the eye or steal its
 * touches: status bar, navigation bar, display cutout, and the gesture-navigation strips down the
 * left and right edges. That last one matters most — an eye snapped flush to the edge on a
 * gesture-navigation device sits exactly where the back swipe lives, and every attempt to grab it
 * navigates back instead.
 */
class OverlayBoundsProvider(context: Context) {

    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = appContext.resources.displayMetrics.density

    /** Extra breathing room so the eye never touches the very edge of the usable area. */
    private val marginPx = (EDGE_MARGIN_DP * density).toInt()

    /**
     * @param anchorView an attached overlay view, if there is one. Its `rootWindowInsets` is the
     *   only accurate inset source below API 30 and stays accurate above it.
     */
    fun currentBounds(anchorView: View?, eyeSizePx: Int): OverlayBounds {
        val (width, height) = displaySize()
        val insets = readInsets(anchorView)

        val left = insets.left + marginPx
        val top = insets.top + marginPx
        val right = width - insets.right - marginPx
        val bottom = height - insets.bottom - marginPx

        val bounds = OverlayBounds(left, top, right, bottom)
        // A pathological inset set (some tablets in split-screen) can invert the rectangle.
        return if (bounds.isUsable(eyeSizePx)) {
            bounds
        } else {
            OverlayBounds(0, 0, width, height)
        }
    }

    /**
     * How far up from the bottom the screen is currently obstructed — the keyboard, mostly.
     * Returns 0 when nothing is in the way.
     */
    fun bottomObstruction(anchorView: View?): Int {
        val view = anchorView ?: return 0
        // The IME inset can only be queried from R onward; below that the keyboard is simply not
        // avoided, which is a cosmetic loss rather than a broken interaction.
        if (!ApiLevel.hasTypedWindowInsets) return 0
        val insets = view.rootWindowInsets ?: return 0
        return insets.getInsets(WindowInsets.Type.ime()).bottom
    }

    private fun displaySize(): Pair<Int, Int> {
        if (ApiLevel.hasWindowMetrics) {
            val metrics = windowManager.currentWindowMetrics
            return metrics.bounds.width() to metrics.bounds.height()
        }
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        val point = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(point)
        return point.x to point.y
    }

    private data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun readInsets(anchorView: View?): Insets {
        val rootInsets = anchorView?.rootWindowInsets
        if (rootInsets != null) {
            val compat = WindowInsetsCompat.toWindowInsetsCompat(rootInsets, anchorView)
            val system = compat.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            val gestures = compat.getInsets(WindowInsetsCompat.Type.systemGestures())
            return Insets(
                left = maxOf(system.left, gestures.left),
                top = system.top,
                right = maxOf(system.right, gestures.right),
                bottom = system.bottom,
            )
        }
        // No attached view yet: fall back to the platform's declared bar heights.
        return Insets(
            left = 0,
            top = resourceHeight("status_bar_height"),
            right = 0,
            bottom = resourceHeight("navigation_bar_height"),
        )
    }

    @Suppress("DiscouragedApi")
    private fun resourceHeight(name: String): Int {
        val id = appContext.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) appContext.resources.getDimensionPixelSize(id) else 0
    }

    fun dpToPx(dp: Int): Int = (dp * density).toInt()

    private companion object {
        const val EDGE_MARGIN_DP = 4
    }
}
