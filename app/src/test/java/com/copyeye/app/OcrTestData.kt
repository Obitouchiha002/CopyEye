package com.copyeye.app

import com.copyeye.app.ocr.OcrBlock
import com.copyeye.app.ocr.OcrLine
import com.copyeye.app.ocr.OcrResult
import com.copyeye.app.ocr.OcrWord
import com.copyeye.app.ocr.TextRect

/**
 * Builders for recognition results.
 *
 * Real ML Kit output cannot be constructed in a JVM test, and mocking it would only test the mock.
 * Building the domain model directly keeps the tests about the logic that maps and selects it.
 */
object OcrTestData {

    /** One line, laid out left to right with evenly spaced words. */
    fun line(
        id: Int,
        words: List<String>,
        top: Float,
        height: Float = 40f,
        startX: Float = 0f,
        wordWidth: Float = 100f,
        gap: Float = 20f,
    ): OcrLine {
        var x = startX
        val ocrWords = words.map { text ->
            val box = TextRect(x, top, x + wordWidth, top + height)
            x += wordWidth + gap
            OcrWord(text, box, confidence = 0.95f)
        }
        return OcrLine(
            id = id,
            text = words.joinToString(" "),
            box = TextRect(
                startX,
                top,
                (x - gap).coerceAtLeast(startX + wordWidth),
                top + height,
            ),
            angleDegrees = 0f,
            words = ocrWords,
            language = "en",
        )
    }

    fun block(id: Int, lines: List<OcrLine>): OcrBlock = OcrBlock(
        id = id,
        text = lines.joinToString("\n") { it.text },
        box = lines.map { it.box }.reduce { acc, rect -> acc.union(rect) },
        lines = lines,
    )

    fun result(blocks: List<OcrBlock>, width: Int = 1080, height: Int = 2400): OcrResult =
        OcrResult(blocks = blocks, sourceWidth = width, sourceHeight = height, elapsedMs = 120L)

    /**
     * Two paragraphs, three lines total:
     *
     * ```
     * block 0:  "Hello world"          (line 0, y 100..140)
     *           "second line here"     (line 1, y 160..200)
     * block 1:  "तीसरी पंक्ति"           (line 2, y 400..440)
     * ```
     */
    fun twoParagraphs(): OcrResult {
        val first = line(id = 0, words = listOf("Hello", "world"), top = 100f)
        val second = line(id = 1, words = listOf("second", "line", "here"), top = 160f)
        val third = line(id = 2, words = listOf("तीसरी", "पंक्ति"), top = 400f)
        return result(listOf(block(0, listOf(first, second)), block(1, listOf(third))))
    }
}
