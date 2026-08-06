package com.copyeye.app

import com.copyeye.app.ocr.OcrResultMapper
import com.copyeye.app.ocr.TextRect
import com.copyeye.app.selection.FrameTransform
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Coordinate mapping.
 *
 * When this is wrong the highlights sit visibly beside the text, and it is wrong in a way that is
 * easy to miss on one device and obvious on another — a crop offset that happens to be zero on the
 * phone you tested on, for instance.
 */
class CoordinateMappingTest {

    @Test
    fun `fitting a portrait frame into a portrait viewport centres it`() {
        val transform = FrameTransform.fit(
            bitmapWidth = 1080,
            bitmapHeight = 2000,
            viewportWidth = 1080f,
            viewportHeight = 2400f,
        )

        assertThat(transform.fitScale).isEqualTo(1f)
        assertThat(transform.fitOffsetX).isEqualTo(0f)
        assertThat(transform.fitOffsetY).isEqualTo(200f)
    }

    @Test
    fun `a frame wider than the viewport is scaled down to fit`() {
        val transform = FrameTransform.fit(2000, 1000, 1000f, 1000f)

        assertThat(transform.fitScale).isEqualTo(0.5f)
        assertThat(transform.fitOffsetY).isEqualTo(250f)
    }

    @Test
    fun `screen and bitmap coordinates round-trip`() {
        val transform = FrameTransform.fit(1080, 2000, 1080f, 2400f)
            .copy(zoom = 2.4f, panX = -300f, panY = -900f)

        val screenX = transform.bitmapToScreenX(640f)
        val screenY = transform.bitmapToScreenY(1_200f)

        assertThat(transform.screenToBitmapX(screenX)).isWithin(0.01f).of(640f)
        assertThat(transform.screenToBitmapY(screenY)).isWithin(0.01f).of(1_200f)
    }

    @Test
    fun `zooming in shrinks the touch tolerance in bitmap terms`() {
        val base = FrameTransform(fitScale = 1f, fitOffsetX = 0f, fitOffsetY = 0f)
        val zoomed = base.copy(zoom = 4f)

        assertThat(base.toleranceInBitmapPixels(40f)).isEqualTo(40f)
        assertThat(zoomed.toleranceInBitmapPixels(40f)).isEqualTo(10f)
    }

    @Test
    fun `a rectangle maps through the transform corner for corner`() {
        val transform = FrameTransform(fitScale = 0.5f, fitOffsetX = 10f, fitOffsetY = 20f)

        val mapped = transform.bitmapToScreen(TextRect(100f, 200f, 300f, 400f))

        assertThat(mapped.left).isEqualTo(60f)
        assertThat(mapped.top).isEqualTo(120f)
        assertThat(mapped.right).isEqualTo(160f)
        assertThat(mapped.bottom).isEqualTo(220f)
    }

    @Test
    fun `mapping OCR results undoes the downscale the pipeline applied`() {
        // The frame was cropped 80px off the top and then halved.
        val result = OcrTestData.result(
            listOf(OcrTestData.block(0, listOf(OcrTestData.line(0, listOf("word"), top = 50f)))),
        )

        val mapped = OcrResultMapper.toDisplaySpace(
            result = result,
            frameScale = 0.5f,
            frameOffsetX = 0,
            frameOffsetY = 80,
        )
        val box = mapped.lines.single().box

        // 50 in half-scale bitmap space is 100 in capture space, plus the 80px crop.
        assertThat(box.top).isEqualTo(180f)
        assertThat(box.left).isEqualTo(0f)
        assertThat(box.right).isEqualTo(200f)
    }

    @Test
    fun `a no-op mapping returns the same object`() {
        val result = OcrTestData.twoParagraphs()

        val mapped = OcrResultMapper.toDisplaySpace(
            result = result,
            frameScale = 1f,
            frameOffsetX = 0,
            frameOffsetY = 0,
        )

        assertThat(mapped).isSameInstanceAs(result)
    }

    @Test
    fun `line hit testing honours the tolerance`() {
        val result = OcrTestData.twoParagraphs()

        assertThat(OcrResultMapper.lineAt(result, 50f, 120f, tolerance = 0f)?.id).isEqualTo(0)
        assertThat(OcrResultMapper.lineAt(result, 50f, 150f, tolerance = 0f)).isNull()
        assertThat(OcrResultMapper.lineAt(result, 50f, 150f, tolerance = 20f)?.id).isEqualTo(0)
    }

    @Test
    fun `lines in a region come back in reading order`() {
        val result = OcrTestData.twoParagraphs()

        val lines = OcrResultMapper.linesIn(result, TextRect(0f, 0f, 2_000f, 2_000f))

        assertThat(lines.map { it.id }).containsExactly(0, 1, 2).inOrder()
    }
}
