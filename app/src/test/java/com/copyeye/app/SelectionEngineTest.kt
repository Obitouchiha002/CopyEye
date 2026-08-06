package com.copyeye.app

import com.copyeye.app.ocr.TextRect
import com.copyeye.app.selection.Selection
import com.copyeye.app.selection.SelectionEngine
import com.copyeye.app.selection.SelectionMode
import com.copyeye.app.selection.WordRef
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SelectionEngineTest {

    private val result = OcrTestData.twoParagraphs()
    private val engine = SelectionEngine(result)

    /** Centre of the word "world" — second word of line 0, so x 120..220, y 100..140. */
    private val worldX = 170f
    private val worldY = 120f

    @Test
    fun `tapping in word mode selects one word`() {
        val selection = engine.selectAt(Selection(), worldX, worldY, tolerance = 0f, SelectionMode.Word)

        assertThat(selection.words).containsExactly(WordRef(lineId = 0, wordIndex = 1))
        assertThat(engine.textOf(selection)).isEqualTo("world")
    }

    @Test
    fun `tapping in line mode selects the whole line`() {
        val selection = engine.selectAt(Selection(), worldX, worldY, tolerance = 0f, SelectionMode.Line)

        assertThat(engine.textOf(selection)).isEqualTo("Hello world")
    }

    @Test
    fun `tapping in paragraph mode selects every line of the block`() {
        val selection =
            engine.selectAt(Selection(), worldX, worldY, tolerance = 0f, SelectionMode.Paragraph)

        assertThat(engine.textOf(selection)).isEqualTo("Hello world\nsecond line here")
    }

    @Test
    fun `tapping the same thing twice clears the selection`() {
        val first = engine.selectAt(Selection(), worldX, worldY, 0f, SelectionMode.Line)
        val second = engine.selectAt(first, worldX, worldY, 0f, SelectionMode.Line)

        assertThat(second.isEmpty).isTrue()
    }

    @Test
    fun `tapping empty space clears the selection`() {
        val selected = engine.selectAt(Selection(), worldX, worldY, 0f, SelectionMode.Line)
        val cleared = engine.selectAt(selected, 900f, 1_800f, tolerance = 0f, SelectionMode.Line)

        assertThat(cleared.isEmpty).isTrue()
    }

    @Test
    fun `tolerance lets a near miss still hit the line`() {
        // Line 0 ends at y=140 and line 1 starts at y=160, so y=145 is just below line 0 and
        // outside a 10px reach of line 1 — the near miss has an unambiguous owner.
        val missed = engine.selectAt(Selection(), worldX, 145f, tolerance = 0f, SelectionMode.Line)
        val forgiven = engine.selectAt(Selection(), worldX, 145f, tolerance = 10f, SelectionMode.Line)

        assertThat(missed.isEmpty).isTrue()
        assertThat(engine.textOf(forgiven)).isEqualTo("Hello world")
    }

    @Test
    fun `a tap between two lines goes to the nearer one`() {
        // Dead centre of the gap: line 1's centre is closer horizontally, so it wins.
        val selection = engine.selectAt(Selection(), worldX, 150f, tolerance = 20f, SelectionMode.Line)

        assertThat(engine.textOf(selection)).isEqualTo("second line here")
    }

    @Test
    fun `dragging across lines picks up everything in between`() {
        val started = engine.beginDrag(50f, 120f, tolerance = 0f) // "Hello"
        val extended = engine.extendDrag(started, 170f, 180f, tolerance = 0f) // "line" on line 1

        assertThat(engine.textOf(extended)).isEqualTo("Hello world\nsecond line")
    }

    @Test
    fun `dragging backwards from the anchor works the same way`() {
        val started = engine.beginDrag(170f, 180f, tolerance = 0f) // "line" on line 1
        val extended = engine.extendDrag(started, 50f, 120f, tolerance = 0f) // back to "Hello"

        assertThat(engine.textOf(extended)).isEqualTo("Hello world\nsecond line")
    }

    @Test
    fun `a drag spanning both blocks separates them with a blank line`() {
        val started = engine.beginDrag(50f, 120f, tolerance = 0f)
        val extended = engine.extendDrag(started, 170f, 420f, tolerance = 0f)

        assertThat(engine.textOf(extended))
            .isEqualTo("Hello world\nsecond line here\n\nतीसरी पंक्ति")
    }

    @Test
    fun `a region selects only what it overlaps`() {
        // A box covering line 1 only.
        val selection = engine.selectRegion(TextRect(0f, 155f, 1_000f, 205f))

        assertThat(engine.textOf(selection)).isEqualTo("second line here")
    }

    @Test
    fun `select all returns every line in reading order`() {
        val selection = engine.selectAll()

        assertThat(engine.textOf(selection))
            .isEqualTo("Hello world\nsecond line here\n\nतीसरी पंक्ति")
        assertThat(selection.size).isEqualTo(7)
    }

    @Test
    fun `a partly selected line joins only the chosen words`() {
        val selection = Selection(
            words = setOf(WordRef(1, 0), WordRef(1, 2)),
            anchor = WordRef(1, 0),
            focus = WordRef(1, 2),
        )

        assertThat(engine.textOf(selection)).isEqualTo("second here")
    }

    @Test
    fun `highlights collapse to one box per line`() {
        val selection = engine.selectAt(Selection(), worldX, worldY, 0f, SelectionMode.Paragraph)

        assertThat(engine.highlightsFor(selection)).hasSize(2)
    }

    @Test
    fun `an empty selection produces no text and no highlights`() {
        assertThat(engine.textOf(Selection())).isEmpty()
        assertThat(engine.highlightsFor(Selection())).isEmpty()
    }

    @Test
    fun `single-line detection is used for the auto-copy shortcut`() {
        val single = SelectionEngine(
            OcrTestData.result(
                listOf(OcrTestData.block(0, listOf(OcrTestData.line(0, listOf("only"), 10f)))),
            ),
        )

        assertThat(single.hasSingleLine).isTrue()
        assertThat(engine.hasSingleLine).isFalse()
    }
}
