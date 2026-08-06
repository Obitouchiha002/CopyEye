package com.copyeye.app.ocr

import kotlin.math.max
import kotlin.math.min

/**
 * A rectangle in some coordinate space, free of Android types.
 *
 * `android.graphics.RectF` would be the obvious choice, but it is a stub in JVM unit tests, which
 * would make the coordinate mapping — the part most likely to be subtly wrong — untestable without
 * an emulator.
 */
data class TextRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = max(0f, width) * max(0f, height)

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun scaled(factor: Float): TextRect =
        TextRect(left * factor, top * factor, right * factor, bottom * factor)

    fun translated(dx: Float, dy: Float): TextRect =
        TextRect(left + dx, top + dy, right + dx, bottom + dy)

    fun expanded(by: Float): TextRect =
        TextRect(left - by, top - by, right + by, bottom + by)

    fun union(other: TextRect): TextRect = TextRect(
        min(left, other.left),
        min(top, other.top),
        max(right, other.right),
        max(bottom, other.bottom),
    )

    fun intersects(other: TextRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** Intersection over union, used to decide whether two recognisers found the same line. */
    fun iou(other: TextRect): Float {
        val interLeft = max(left, other.left)
        val interTop = max(top, other.top)
        val interRight = min(right, other.right)
        val interBottom = min(bottom, other.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val intersection = (interRight - interLeft) * (interBottom - interTop)
        val unionArea = area + other.area - intersection
        return if (unionArea <= 0f) 0f else intersection / unionArea
    }

    companion object {
        val EMPTY = TextRect(0f, 0f, 0f, 0f)
    }
}

/** A single recognised word. The smallest unit the user can tap. */
data class OcrWord(
    val text: String,
    val box: TextRect,
    val confidence: Float?,
)

/** One recognised line. Tapping anywhere on a line selects the whole line. */
data class OcrLine(
    val id: Int,
    val text: String,
    val box: TextRect,
    val angleDegrees: Float,
    val words: List<OcrWord>,
    val language: String?,
)

/** A paragraph-sized grouping, as ML Kit's block. Selectable as a unit. */
data class OcrBlock(
    val id: Int,
    val text: String,
    val box: TextRect,
    val lines: List<OcrLine>,
)

/**
 * Everything recognised in one frame.
 *
 * [sourceWidth]/[sourceHeight] are the dimensions of the bitmap the boxes refer to, which is
 * usually a downscaled crop rather than the screen. [OcrResultMapper] is what turns those boxes
 * into screen coordinates.
 */
data class OcrResult(
    val blocks: List<OcrBlock>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val elapsedMs: Long,
) {
    val lines: List<OcrLine> get() = blocks.flatMap { it.lines }
    val isEmpty: Boolean get() = blocks.isEmpty()

    /** All detected text in reading order, blocks separated by a blank line. */
    val fullText: String
        get() = blocks.joinToString("\n\n") { block ->
            block.lines.joinToString("\n") { it.text }
        }

    companion object {
        fun empty(width: Int, height: Int, elapsedMs: Long = 0L) =
            OcrResult(emptyList(), width, height, elapsedMs)
    }
}
