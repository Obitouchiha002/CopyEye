package com.copyeye.app

import com.copyeye.app.ocr.TextRect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The geometry the two-recogniser merge relies on to decide that two lines are the same line. */
class TextRectTest {

    @Test
    fun `identical rectangles overlap completely`() {
        val rect = TextRect(0f, 0f, 100f, 50f)

        assertThat(rect.iou(rect)).isEqualTo(1f)
    }

    @Test
    fun `disjoint rectangles do not overlap at all`() {
        val a = TextRect(0f, 0f, 100f, 50f)
        val b = TextRect(200f, 0f, 300f, 50f)

        assertThat(a.iou(b)).isEqualTo(0f)
    }

    @Test
    fun `touching edges count as no overlap`() {
        val a = TextRect(0f, 0f, 100f, 50f)
        val b = TextRect(100f, 0f, 200f, 50f)

        assertThat(a.iou(b)).isEqualTo(0f)
        assertThat(a.intersects(b)).isFalse()
    }

    @Test
    fun `a half overlap scores a third, as intersection over union`() {
        val a = TextRect(0f, 0f, 100f, 100f)
        val b = TextRect(50f, 0f, 150f, 100f)

        // Intersection 5000, union 15000.
        assertThat(a.iou(b)).isWithin(0.001f).of(1f / 3f)
    }

    @Test
    fun `union covers both rectangles`() {
        val union = TextRect(0f, 10f, 50f, 60f).union(TextRect(40f, 0f, 100f, 30f))

        assertThat(union).isEqualTo(TextRect(0f, 0f, 100f, 60f))
    }

    @Test
    fun `expanding grows in every direction`() {
        val expanded = TextRect(10f, 10f, 20f, 20f).expanded(5f)

        assertThat(expanded).isEqualTo(TextRect(5f, 5f, 25f, 25f))
    }

    @Test
    fun `contains is inclusive of the edges`() {
        val rect = TextRect(0f, 0f, 10f, 10f)

        assertThat(rect.contains(0f, 0f)).isTrue()
        assertThat(rect.contains(10f, 10f)).isTrue()
        assertThat(rect.contains(10.1f, 5f)).isFalse()
    }

    @Test
    fun `a zero-area rectangle has no overlap with anything`() {
        val empty = TextRect(5f, 5f, 5f, 5f)

        assertThat(empty.iou(TextRect(0f, 0f, 10f, 10f))).isEqualTo(0f)
    }
}
