package com.copyeye.app.ocr

import com.copyeye.app.capture.ScreenFrame

/**
 * Turns recognition coordinates back into coordinates the user can touch.
 *
 * ML Kit reports boxes in the pixel space of whatever bitmap it was handed. By the time OCR runs
 * that bitmap has usually been cropped (system bars removed) and downscaled, so every box is
 * offset and shrunk relative to the real screen. Getting this wrong is not a subtle bug — the
 * highlights land visibly beside the text — but it is easy to get wrong in exactly one direction
 * and never notice on the device you happen to be testing on. Hence a pure, unit-tested mapper.
 */
object OcrResultMapper {

    /**
     * @param frame the frame OCR ran on, carrying the crop offset and scale that produced it.
     * @param displayScale extra factor applied when the frozen frame is drawn at a different size
     *   than the screen it came from — for example inside a zoomed selection view.
     */
    fun toDisplaySpace(
        result: OcrResult,
        frame: ScreenFrame,
        displayScale: Float = 1f,
        displayOffsetX: Float = 0f,
        displayOffsetY: Float = 0f,
    ): OcrResult = toDisplaySpace(
        result = result,
        frameScale = frame.scale,
        frameOffsetX = frame.contentOffsetX,
        frameOffsetY = frame.contentOffsetY,
        displayScale = displayScale,
        displayOffsetX = displayOffsetX,
        displayOffsetY = displayOffsetY,
    )

    /**
     * The same mapping expressed in plain numbers.
     *
     * [ScreenFrame] carries a `Bitmap`, which cannot be constructed in a JVM unit test, so the
     * arithmetic lives here where it can be verified without an emulator.
     */
    fun toDisplaySpace(
        result: OcrResult,
        frameScale: Float,
        frameOffsetX: Int,
        frameOffsetY: Int,
        displayScale: Float = 1f,
        displayOffsetX: Float = 0f,
        displayOffsetY: Float = 0f,
    ): OcrResult {
        val inverse = if (frameScale <= 0f) 1f else 1f / frameScale
        val factor = inverse * displayScale
        val dx = frameOffsetX * displayScale + displayOffsetX
        val dy = frameOffsetY * displayScale + displayOffsetY

        if (factor == 1f && dx == 0f && dy == 0f) return result

        fun map(rect: TextRect) = rect.scaled(factor).translated(dx, dy)

        return result.copy(
            blocks = result.blocks.map { block ->
                block.copy(
                    box = map(block.box),
                    lines = block.lines.map { line ->
                        line.copy(
                            box = map(line.box),
                            words = line.words.map { word -> word.copy(box = map(word.box)) },
                        )
                    },
                )
            },
        )
    }

    /**
     * The line whose box contains the point, or — when nothing does — the nearest line within
     * [tolerance].
     *
     * A tolerance is necessary because recognised boxes hug the glyphs tightly, so a finger aimed
     * at the middle of a short line of small text frequently lands a few pixels outside it.
     */
    fun lineAt(result: OcrResult, x: Float, y: Float, tolerance: Float = 0f): OcrLine? {
        val lines = result.lines
        lines.firstOrNull { it.box.contains(x, y) }?.let { return it }
        if (tolerance <= 0f) return null
        return lines
            .filter { it.box.expanded(tolerance).contains(x, y) }
            .minByOrNull { distanceSquared(it.box, x, y) }
    }

    /** The word under the point, searched only inside the line that was hit. */
    fun wordAt(result: OcrResult, x: Float, y: Float, tolerance: Float = 0f): OcrWord? {
        val line = lineAt(result, x, y, tolerance) ?: return null
        line.words.firstOrNull { it.box.contains(x, y) }?.let { return it }
        return line.words.minByOrNull { distanceSquared(it.box, x, y) }
    }

    /** The block under the point. */
    fun blockAt(result: OcrResult, x: Float, y: Float, tolerance: Float = 0f): OcrBlock? {
        result.blocks.firstOrNull { it.box.contains(x, y) }?.let { return it }
        if (tolerance <= 0f) return null
        return result.blocks
            .filter { it.box.expanded(tolerance).contains(x, y) }
            .minByOrNull { distanceSquared(it.box, x, y) }
    }

    /** Every line whose box intersects the given region, in reading order. */
    fun linesIn(result: OcrResult, region: TextRect): List<OcrLine> =
        result.lines
            .filter { it.box.intersects(region) }
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))

    /** Every word inside the region, in reading order. */
    fun wordsIn(result: OcrResult, region: TextRect): List<OcrWord> =
        result.lines
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
            .flatMap { line -> line.words.filter { it.box.intersects(region) } }

    private fun distanceSquared(rect: TextRect, x: Float, y: Float): Float {
        val dx = (x - rect.centerX)
        val dy = (y - rect.centerY)
        return dx * dx + dy * dy
    }
}
