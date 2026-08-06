package com.copyeye.app

import com.google.common.truth.Truth.assertThat
import com.copyeye.app.overlay.DragGestureHandler
import com.copyeye.app.overlay.EyeGesture
import org.junit.Test

/**
 * The tap-versus-drag boundary.
 *
 * This is the behaviour that decides whether the product is usable: if a drag can also fire a scan,
 * every attempt to move the eye interrupts whatever the user was watching.
 */
class DragGestureHandlerTest {

    private fun handler() = DragGestureHandler(touchSlopPx = 10)

    @Test
    fun `a still press and quick release is a tap`() {
        val handler = handler()
        handler.onDown(100f, 100f, 0L)
        assertThat(handler.onUp(102f, 101f, 90L)).isEmpty()

        // The tap is withheld until the double-tap window closes.
        val afterWindow = handler.onTick(90L + DragGestureHandler.DEFAULT_DOUBLE_TAP_MS)
        assertThat(afterWindow).containsExactly(EyeGesture.Tap)
    }

    @Test
    fun `movement past the slop becomes a drag and never a tap`() {
        val handler = handler()
        handler.onDown(100f, 100f, 0L)
        val moved = handler.onMove(140f, 100f, 40L)

        assertThat(moved.first()).isEqualTo(EyeGesture.DragStarted)
        assertThat(handler.isDragging).isTrue()

        val released = handler.onUp(140f, 100f, 80L)
        assertThat(released).containsExactly(EyeGesture.DragEnded(40, 0))
        // Crucially, no Tap is ever produced by this stream.
        assertThat(handler.onTick(1_000L)).isEmpty()
    }

    @Test
    fun `movement inside the slop stays a tap`() {
        val handler = handler()
        handler.onDown(100f, 100f, 0L)
        assertThat(handler.onMove(105f, 103f, 20L)).isEmpty()
        assertThat(handler.isDragging).isFalse()

        handler.onUp(105f, 103f, 60L)
        assertThat(handler.onTick(60L + DragGestureHandler.DEFAULT_DOUBLE_TAP_MS))
            .containsExactly(EyeGesture.Tap)
    }

    @Test
    fun `holding still past the timeout is a long press`() {
        val handler = handler()
        handler.onDown(100f, 100f, 0L)

        assertThat(handler.onTick(DragGestureHandler.DEFAULT_LONG_PRESS_MS - 1))
            .doesNotContain(EyeGesture.LongPress)
        assertThat(handler.onTick(DragGestureHandler.DEFAULT_LONG_PRESS_MS))
            .containsExactly(EyeGesture.LongPress)
    }

    @Test
    fun `a long press does not also produce a tap on release`() {
        val handler = handler()
        handler.onDown(100f, 100f, 0L)
        handler.onTick(DragGestureHandler.DEFAULT_LONG_PRESS_MS)

        assertThat(handler.onUp(100f, 100f, 600L)).isEmpty()
        assertThat(handler.onTick(2_000L)).isEmpty()
    }

    @Test
    fun `dragging cancels a long press that has not fired`() {
        val handler = handler()
        handler.onDown(100f, 100f, 0L)
        handler.onMove(200f, 100f, 100L)

        assertThat(handler.onTick(DragGestureHandler.DEFAULT_LONG_PRESS_MS))
            .doesNotContain(EyeGesture.LongPress)
    }

    @Test
    fun `two quick taps are one double tap, not two taps`() {
        val handler = handler()
        handler.onDown(100f, 100f, 0L)
        handler.onUp(100f, 100f, 60L)

        handler.onDown(100f, 100f, 120L)
        val second = handler.onUp(100f, 100f, 160L)

        assertThat(second).containsExactly(EyeGesture.DoubleTap)
        // The first tap must not surface afterwards.
        assertThat(handler.onTick(5_000L)).isEmpty()
    }

    @Test
    fun `taps far apart in time are two separate taps`() {
        val handler = handler()
        handler.onDown(100f, 100f, 0L)
        handler.onUp(100f, 100f, 50L)
        assertThat(handler.onTick(50L + DragGestureHandler.DEFAULT_DOUBLE_TAP_MS))
            .containsExactly(EyeGesture.Tap)

        handler.onDown(100f, 100f, 2_000L)
        assertThat(handler.onUp(100f, 100f, 2_050L)).isEmpty()
        assertThat(handler.onTick(2_050L + DragGestureHandler.DEFAULT_DOUBLE_TAP_MS))
            .containsExactly(EyeGesture.Tap)
    }

    @Test
    fun `a press held far too long is discarded rather than becoming a tap`() {
        val handler = handler()
        handler.onDown(100f, 100f, 0L)
        // No tick, so no long press fired; the release is simply stale.
        assertThat(handler.onUp(100f, 100f, DragGestureHandler.DEFAULT_TAP_MAX_MS + 1))
            .containsExactly(EyeGesture.Cancelled)
    }

    @Test
    fun `cancelling mid-drag reports a cancellation`() {
        val handler = handler()
        handler.onDown(100f, 100f, 0L)
        handler.onMove(300f, 100f, 40L)

        assertThat(handler.onCancel()).containsExactly(EyeGesture.Cancelled)
        assertThat(handler.isDragging).isFalse()
    }
}
