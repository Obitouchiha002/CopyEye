package com.copyeye.app.capture

import android.graphics.Bitmap
import android.graphics.Rect
import com.copyeye.app.data.preferences.OcrMode
import kotlin.math.roundToInt

/**
 * Prepares a captured frame for recognition.
 *
 * The pipeline is deliberately thin. Every extra step here is time the user spends staring at a
 * scan animation, and ML Kit's own preprocessing already handles contrast and binarisation better
 * than a hand-rolled pass would. So this does exactly two things: crop what cannot contain useful
 * text, and cap the resolution.
 */
object FrameProcessor {

    /**
     * Longest edge fed to the recogniser.
     *
     * ML Kit needs roughly 20px of glyph height for reliable results. A 1440p phone screen renders
     * body text at about 45px tall, so halving it still leaves ample margin while cutting the
     * pixels ML Kit has to walk by a factor of four. Accurate mode raises the cap for small text
     * such as image captions and code.
     */
    private const val FAST_MAX_EDGE = 1280
    private const val ACCURATE_MAX_EDGE = 1920

    /**
     * Downscales when it helps and crops the given insets.
     *
     * @param cropInsets area of the raw capture to keep, in capture pixels. Usually the screen
     *   minus the status and navigation bars, neither of which carries text worth copying.
     */
    fun prepare(
        frame: ScreenFrame,
        mode: OcrMode,
        cropInsets: Rect? = null,
        lowPerformanceMode: Boolean = false,
    ): ScreenFrame {
        val source = frame.bitmap
        if (source.isRecycled) return frame

        val crop = cropInsets?.let { clampCrop(it, source.width, source.height) }
        val cropped = if (crop != null && !isFullFrame(crop, source)) {
            Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
        } else {
            source
        }

        val maxEdge = when {
            lowPerformanceMode -> FAST_MAX_EDGE * 3 / 4
            mode == OcrMode.Accurate -> ACCURATE_MAX_EDGE
            else -> FAST_MAX_EDGE
        }

        val longest = maxOf(cropped.width, cropped.height)
        val scale = if (longest > maxEdge) maxEdge.toFloat() / longest else 1f

        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                cropped,
                (cropped.width * scale).roundToInt().coerceAtLeast(1),
                (cropped.height * scale).roundToInt().coerceAtLeast(1),
                /* filter = */ true,
            )
        } else {
            cropped
        }

        // Release intermediates, but never the caller's original if it was passed straight through.
        if (cropped !== source && cropped !== scaled) cropped.recycle()

        if (scaled === source) return frame

        return ScreenFrame(
            bitmap = scaled,
            screenWidth = frame.screenWidth,
            screenHeight = frame.screenHeight,
            contentOffsetX = frame.contentOffsetX + (crop?.left ?: 0),
            contentOffsetY = frame.contentOffsetY + (crop?.top ?: 0),
            scale = frame.scale * scale,
            rotationDegrees = frame.rotationDegrees,
        ).also {
            // The original is no longer referenced by the returned frame.
            if (source !== scaled) source.recycle()
        }
    }

    private fun clampCrop(rect: Rect, width: Int, height: Int): Rect = Rect(
        rect.left.coerceIn(0, width - 1),
        rect.top.coerceIn(0, height - 1),
        rect.right.coerceIn(1, width),
        rect.bottom.coerceIn(1, height),
    ).also {
        if (it.width() <= 0 || it.height() <= 0) it.set(0, 0, width, height)
    }

    private fun isFullFrame(crop: Rect, bitmap: Bitmap): Boolean =
        crop.left == 0 && crop.top == 0 && crop.right == bitmap.width && crop.bottom == bitmap.height
}
