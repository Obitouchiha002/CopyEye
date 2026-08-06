package com.copyeye.app.core.state

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** One-off things worth telling the rest of the app about. */
sealed interface CopyEyeEvent {
    data class Copied(val characterCount: Int) : CopyEyeEvent
    data object ScanBlockedBySecureScreen : CopyEyeEvent
    data object NoTextFound : CopyEyeEvent
    data class Failed(val error: CopyEyeError) : CopyEyeEvent
    data object ProjectionLost : CopyEyeEvent
}

/**
 * The single shared view of what CopyEye is doing right now.
 *
 * The overlay service, the selection activity and the app's own screens are three separate
 * lifecycles in one process, and all three need the same answer to "is the eye running, and what is
 * it doing". Passing that around through intents would mean three copies that drift; a process-wide
 * observable is the honest shape.
 *
 * Note what is deliberately *not* here: the copied text itself. The Home screen shows a character
 * count and nothing more, so recognised text never has to sit in a process-wide singleton where a
 * stray log statement could reach it.
 */
object CopyEyeBus {

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _state = MutableStateFlow(CopyEyeState.Disabled)
    val state: StateFlow<CopyEyeState> = _state.asStateFlow()

    private val _projectionActive = MutableStateFlow(false)
    val projectionActive: StateFlow<Boolean> = _projectionActive.asStateFlow()

    private val _events = MutableSharedFlow<CopyEyeEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<CopyEyeEvent> = _events.asSharedFlow()

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
        if (!running) {
            _state.value = CopyEyeState.Disabled
            _projectionActive.value = false
        }
    }

    fun setProjectionActive(active: Boolean) {
        _projectionActive.value = active
    }

    fun setState(state: CopyEyeState) {
        _state.value = state
    }

    fun emit(event: CopyEyeEvent) {
        _events.tryEmit(event)
    }
}
