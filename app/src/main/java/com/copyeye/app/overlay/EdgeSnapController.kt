package com.copyeye.app.overlay

import com.copyeye.app.data.preferences.PreferredEdge
import kotlin.math.roundToInt

/** The rectangle the eye is allowed to occupy, already inset for bars, cutouts and gesture areas. */
data class OverlayBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun isUsable(eyeSize: Int): Boolean = width >= eyeSize && height >= eyeSize
}

enum class SnapEdge { Left, Right }

/** A resolved resting place for the eye, in absolute window coordinates. */
data class EyePlacement(
    val x: Int,
    val y: Int,
    val edge: SnapEdge,
)

/**
 * Decides where the eye comes to rest.
 *
 * All of this is deliberately free of Android types so the geometry — which is where the awkward
 * bugs live, especially around rotation and cutouts — can be tested on the JVM.
 */
object EdgeSnapController {

    /**
     * Snaps a released eye to the nearer horizontal edge.
     *
     * @param peekFraction how much of the eye tucks off-screen once it is idle, 0f..0.5f.
     *   The snap itself always leaves the eye fully visible; peeking is applied separately by
     *   [peekOffsetFor] when the dim timer fires, so a user who grabs the eye again finds it where
     *   they left it.
     */
    fun snap(
        x: Int,
        y: Int,
        eyeSize: Int,
        bounds: OverlayBounds,
        preferredEdge: PreferredEdge = PreferredEdge.Auto,
        margin: Int = 0,
    ): EyePlacement {
        if (!bounds.isUsable(eyeSize)) {
            return EyePlacement(bounds.left, bounds.top, SnapEdge.Left)
        }
        val centreX = x + eyeSize / 2
        val edge = when (preferredEdge) {
            PreferredEdge.Left -> SnapEdge.Left
            PreferredEdge.Right -> SnapEdge.Right
            PreferredEdge.Auto ->
                if (centreX <= bounds.left + bounds.width / 2) SnapEdge.Left else SnapEdge.Right
        }
        val snappedX = when (edge) {
            SnapEdge.Left -> bounds.left + margin
            SnapEdge.Right -> bounds.right - eyeSize - margin
        }
        val clampedY = y.coerceIn(bounds.top, bounds.bottom - eyeSize)
        return EyePlacement(snappedX.coerceIn(bounds.left, bounds.right - eyeSize), clampedY, edge)
    }

    /** Keeps a dragging eye inside the safe area without snapping it. */
    fun clamp(x: Int, y: Int, eyeSize: Int, bounds: OverlayBounds): EyePlacement {
        if (!bounds.isUsable(eyeSize)) return EyePlacement(bounds.left, bounds.top, SnapEdge.Left)
        val cx = x.coerceIn(bounds.left, bounds.right - eyeSize)
        val cy = y.coerceIn(bounds.top, bounds.bottom - eyeSize)
        val edge = if (cx + eyeSize / 2 <= bounds.left + bounds.width / 2) SnapEdge.Left else SnapEdge.Right
        return EyePlacement(cx, cy, edge)
    }

    /**
     * Horizontal offset that hides [peekFraction] of the eye behind its edge.
     * Negative moves left, positive moves right.
     */
    fun peekOffsetFor(edge: SnapEdge, eyeSize: Int, peekFraction: Float): Int {
        val hidden = (eyeSize * peekFraction.coerceIn(0f, 0.5f)).roundToInt()
        return if (edge == SnapEdge.Left) -hidden else hidden
    }

    /**
     * Re-expresses a placement in new bounds, keeping the same proportional position.
     *
     * Used on rotation, on entering split-screen, and when the navigation mode changes. Storing a
     * fraction rather than a pixel value is what stops the eye from landing under the navigation
     * bar after a rotate.
     */
    fun remap(
        placement: EyePlacement,
        eyeSize: Int,
        from: OverlayBounds,
        to: OverlayBounds,
        preferredEdge: PreferredEdge = PreferredEdge.Auto,
    ): EyePlacement {
        val yFraction = if (from.height - eyeSize <= 0) {
            0f
        } else {
            (placement.y - from.top).toFloat() / (from.height - eyeSize).toFloat()
        }
        val newY = to.top + (yFraction.coerceIn(0f, 1f) * (to.height - eyeSize)).roundToInt()
        val anchorX = when (placement.edge) {
            SnapEdge.Left -> to.left
            SnapEdge.Right -> to.right - eyeSize
        }
        return snap(anchorX, newY, eyeSize, to, preferredEdge)
    }

    /** Converts an absolute placement into the fractions persisted in settings. */
    fun toFractions(placement: EyePlacement, eyeSize: Int, bounds: OverlayBounds): Pair<Float, Float> {
        val xSpan = (bounds.width - eyeSize).coerceAtLeast(1)
        val ySpan = (bounds.height - eyeSize).coerceAtLeast(1)
        return (placement.x - bounds.left).toFloat() / xSpan to
            (placement.y - bounds.top).toFloat() / ySpan
    }

    /** Inverse of [toFractions]. */
    fun fromFractions(
        xFraction: Float,
        yFraction: Float,
        eyeSize: Int,
        bounds: OverlayBounds,
        preferredEdge: PreferredEdge = PreferredEdge.Auto,
    ): EyePlacement {
        val x = bounds.left + (xFraction.coerceIn(0f, 1f) * (bounds.width - eyeSize)).roundToInt()
        val y = bounds.top + (yFraction.coerceIn(0f, 1f) * (bounds.height - eyeSize)).roundToInt()
        return snap(x, y, eyeSize, bounds, preferredEdge)
    }

    /**
     * Nudges the eye up when something claims the bottom of the screen — usually the keyboard, but
     * also picture-in-picture windows and media controls.
     *
     * @param obstructedBottom the y coordinate below which the eye must not sit.
     */
    fun avoidObstruction(
        placement: EyePlacement,
        eyeSize: Int,
        bounds: OverlayBounds,
        obstructedBottom: Int,
        gap: Int,
    ): EyePlacement {
        val maxY = (obstructedBottom - eyeSize - gap)
        if (placement.y <= maxY) return placement
        val newY = maxY.coerceAtLeast(bounds.top)
        return placement.copy(y = newY.coerceAtMost(bounds.bottom - eyeSize))
    }
}
