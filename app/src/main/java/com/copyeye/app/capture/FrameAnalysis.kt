package com.copyeye.app.capture

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Cheap statistics over a captured frame.
 *
 * These decide two things the user sees directly: whether to show "this screen is protected"
 * instead of "no text found", and — in Smart Frame Mode — which of a short burst of video frames
 * is the least blurry.
 */
object FrameAnalysis {

    /** Grid resolution used for sampling. 32x32 = 1024 pixel reads, ~0.1 ms. */
    private const val GRID = 32

    /** A frame this uniform is not a real screen. */
    private const val UNIFORMITY_THRESHOLD = 3.0

    /**
     * True when the frame carries no visible content.
     *
     * A `FLAG_SECURE` window is composited into a media projection as solid black, so a frame that
     * is uniformly black (or uniformly anything) means we were handed a blanked capture rather than
     * a screen with nothing on it. The check is a variance test rather than a pure black test
     * because some vendors blank to solid white or to the wallpaper's average colour.
     */
    fun isBlank(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width == 0 || bitmap.height == 0) return true
        val samples = sampleLuma(bitmap)
        if (samples.isEmpty()) return true
        val mean = samples.average()
        val variance = samples.sumOf { (it - mean) * (it - mean) } / samples.size
        return sqrt(variance) < UNIFORMITY_THRESHOLD
    }

    /**
     * A rough sharpness score: the mean absolute difference between horizontally adjacent samples.
     * Higher is sharper. Only comparable between frames of the same scene and the same size.
     */
    fun sharpness(bitmap: Bitmap): Double {
        if (bitmap.isRecycled || bitmap.width < 2 || bitmap.height < 2) return 0.0
        val step = max(1, bitmap.width / GRID)
        val rowStep = max(1, bitmap.height / GRID)
        var total = 0.0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            var previous = -1.0
            while (x < bitmap.width) {
                val luma = luma(bitmap.getPixel(x, y))
                if (previous >= 0) {
                    total += abs(luma - previous)
                    count++
                }
                previous = luma
                x += step
            }
            y += rowStep
        }
        return if (count == 0) 0.0 else total / count
    }

    private fun sampleLuma(bitmap: Bitmap): DoubleArray {
        val stepX = max(1, bitmap.width / GRID)
        val stepY = max(1, bitmap.height / GRID)
        val out = ArrayList<Double>(GRID * GRID)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                out += luma(bitmap.getPixel(x, y))
                x += stepX
            }
            y += stepY
        }
        return out.toDoubleArray()
    }

    private fun luma(pixel: Int): Double =
        0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)
}
