package com.copyeye.app

import com.copyeye.app.core.state.CopyEyeState
import com.copyeye.app.core.state.CopyEyeStateMachine
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The transitions that must never be legal.
 *
 * Each of these corresponds to a real failure mode: a drag that also scans, a double tap that
 * launches two OCR jobs, an overlay that survives its own service.
 */
class StateMachineTest {

    @Test
    fun `a drag can never become a scan`() {
        assertThat(CopyEyeStateMachine.canTransition(CopyEyeState.Dragging, CopyEyeState.Capturing))
            .isFalse()
        assertThat(CopyEyeStateMachine.canTransition(CopyEyeState.Dragging, CopyEyeState.Scanning))
            .isFalse()
    }

    @Test
    fun `a drag returns to idle`() {
        assertThat(CopyEyeStateMachine.transition(CopyEyeState.Dragging, CopyEyeState.EyeIdle))
            .isEqualTo(CopyEyeState.EyeIdle)
    }

    @Test
    fun `an illegal transition leaves the state untouched`() {
        val result = CopyEyeStateMachine.transition(CopyEyeState.Dragging, CopyEyeState.Copied)

        assertThat(result).isEqualTo(CopyEyeState.Dragging)
    }

    @Test
    fun `a scan in flight cannot start another capture`() {
        assertThat(CopyEyeStateMachine.canTransition(CopyEyeState.Scanning, CopyEyeState.Capturing))
            .isFalse()
    }

    @Test
    fun `the happy path is walkable end to end`() {
        val path = listOf(
            CopyEyeState.Disabled,
            CopyEyeState.Ready,
            CopyEyeState.EyeIdle,
            CopyEyeState.Capturing,
            CopyEyeState.Scanning,
            CopyEyeState.TextDetected,
            CopyEyeState.Selecting,
            CopyEyeState.Copying,
            CopyEyeState.Copied,
            CopyEyeState.EyeIdle,
        )

        path.zipWithNext().forEach { (from, to) ->
            assertThat(CopyEyeStateMachine.canTransition(from, to)).isTrue()
        }
    }

    @Test
    fun `every state can be torn down to Disabled`() {
        CopyEyeState.entries.forEach { state ->
            assertThat(CopyEyeStateMachine.canTransition(state, CopyEyeState.Disabled)).isTrue()
        }
    }

    @Test
    fun `staying in the same state is always allowed`() {
        CopyEyeState.entries.forEach { state ->
            assertThat(CopyEyeStateMachine.canTransition(state, state)).isTrue()
        }
    }

    @Test
    fun `a secure screen ends the scan rather than continuing it`() {
        assertThat(CopyEyeStateMachine.canTransition(CopyEyeState.SecureScreen, CopyEyeState.Scanning))
            .isFalse()
        assertThat(CopyEyeStateMachine.canTransition(CopyEyeState.SecureScreen, CopyEyeState.EyeIdle))
            .isTrue()
    }

    @Test
    fun `scan-in-flight covers exactly capture and recognition`() {
        val inFlight = CopyEyeState.entries.filter { it.isScanInFlight }

        assertThat(inFlight).containsExactly(CopyEyeState.Capturing, CopyEyeState.Scanning)
    }

    @Test
    fun `dimming and waking are both legal`() {
        assertThat(CopyEyeStateMachine.canTransition(CopyEyeState.EyeIdle, CopyEyeState.EyeDimmed))
            .isTrue()
        assertThat(CopyEyeStateMachine.canTransition(CopyEyeState.EyeDimmed, CopyEyeState.EyeIdle))
            .isTrue()
    }

    @Test
    fun `a dimmed eye can still be tapped to scan`() {
        assertThat(CopyEyeStateMachine.canTransition(CopyEyeState.EyeDimmed, CopyEyeState.Capturing))
            .isTrue()
    }
}
