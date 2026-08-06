package com.copyeye.app

import com.copyeye.app.data.preferences.PreferredEdge
import com.copyeye.app.overlay.EdgeSnapController
import com.copyeye.app.overlay.OverlayBounds
import com.copyeye.app.overlay.SnapEdge
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EdgeSnapControllerTest {

    /** A 1080x2400 phone with a status bar and a gesture bar taken out. */
    private val bounds = OverlayBounds(left = 0, top = 80, right = 1080, bottom = 2340)
    private val eye = 126 // 42dp at 3x

    @Test
    fun `an eye on the left half snaps left`() {
        val placement = EdgeSnapController.snap(x = 300, y = 800, eyeSize = eye, bounds = bounds)

        assertThat(placement.edge).isEqualTo(SnapEdge.Left)
        assertThat(placement.x).isEqualTo(bounds.left)
        assertThat(placement.y).isEqualTo(800)
    }

    @Test
    fun `an eye on the right half snaps right`() {
        val placement = EdgeSnapController.snap(x = 700, y = 800, eyeSize = eye, bounds = bounds)

        assertThat(placement.edge).isEqualTo(SnapEdge.Right)
        assertThat(placement.x).isEqualTo(bounds.right - eye)
    }

    @Test
    fun `a preferred edge overrides which half the eye was released in`() {
        val placement = EdgeSnapController.snap(
            x = 20, y = 800, eyeSize = eye, bounds = bounds, preferredEdge = PreferredEdge.Right,
        )

        assertThat(placement.edge).isEqualTo(SnapEdge.Right)
    }

    @Test
    fun `snapping never leaves the eye outside the safe area`() {
        val above = EdgeSnapController.snap(x = 500, y = -400, eyeSize = eye, bounds = bounds)
        val below = EdgeSnapController.snap(x = 500, y = 9_000, eyeSize = eye, bounds = bounds)

        assertThat(above.y).isEqualTo(bounds.top)
        assertThat(below.y).isEqualTo(bounds.bottom - eye)
    }

    @Test
    fun `clamping during a drag keeps the eye inside without snapping`() {
        val clamped = EdgeSnapController.clamp(x = 540, y = 1_200, eyeSize = eye, bounds = bounds)

        assertThat(clamped.x).isEqualTo(540)
        assertThat(clamped.y).isEqualTo(1_200)
    }

    @Test
    fun `peek offset hides part of the eye behind the correct side`() {
        val left = EdgeSnapController.peekOffsetFor(SnapEdge.Left, eyeSize = 100, peekFraction = 0.3f)
        val right = EdgeSnapController.peekOffsetFor(SnapEdge.Right, eyeSize = 100, peekFraction = 0.3f)

        assertThat(left).isEqualTo(-30)
        assertThat(right).isEqualTo(30)
    }

    @Test
    fun `peek is capped at half the eye so it can never vanish entirely`() {
        val extreme = EdgeSnapController.peekOffsetFor(SnapEdge.Right, eyeSize = 100, peekFraction = 5f)

        assertThat(extreme).isEqualTo(50)
    }

    @Test
    fun `rotation keeps the eye at the same proportional height`() {
        val portrait = OverlayBounds(0, 80, 1080, 2340)
        val landscape = OverlayBounds(80, 0, 2340, 1080)

        // Sitting a quarter of the way down the portrait screen.
        val start = EdgeSnapController.snap(1_000, 645, eye, portrait)
        val rotated = EdgeSnapController.remap(start, eye, portrait, landscape)

        val startFraction = (start.y - portrait.top).toFloat() / (portrait.height - eye)
        val endFraction = (rotated.y - landscape.top).toFloat() / (landscape.height - eye)

        assertThat(endFraction).isWithin(0.02f).of(startFraction)
        assertThat(rotated.edge).isEqualTo(SnapEdge.Right)
        assertThat(rotated.x).isEqualTo(landscape.right - eye)
    }

    @Test
    fun `fractions round-trip through a placement`() {
        val original = EdgeSnapController.snap(900, 1_500, eye, bounds)
        val (fx, fy) = EdgeSnapController.toFractions(original, eye, bounds)
        val restored = EdgeSnapController.fromFractions(fx, fy, eye, bounds)

        assertThat(restored.x).isEqualTo(original.x)
        // Rounding through a fraction can move the eye by a pixel; more than that is a bug.
        assertThat(Math.abs(restored.y - original.y)).isAtMost(2)
    }

    @Test
    fun `the keyboard pushes the eye up by exactly enough`() {
        val resting = EdgeSnapController.snap(1_000, 2_100, eye, bounds)
        val imeTop = 1_600

        val moved = EdgeSnapController.avoidObstruction(resting, eye, bounds, imeTop, gap = 30)

        assertThat(moved.y).isEqualTo(imeTop - eye - 30)
    }

    @Test
    fun `an eye already clear of the keyboard is left alone`() {
        val resting = EdgeSnapController.snap(1_000, 400, eye, bounds)

        val moved = EdgeSnapController.avoidObstruction(resting, eye, bounds, 1_600, gap = 30)

        assertThat(moved).isEqualTo(resting)
    }

    @Test
    fun `degenerate bounds do not produce a negative placement`() {
        val tiny = OverlayBounds(0, 0, 10, 10)

        val placement = EdgeSnapController.snap(5, 5, eyeSize = 200, bounds = tiny)

        assertThat(placement.x).isAtLeast(0)
        assertThat(placement.y).isAtLeast(0)
    }
}
