package com.copyeye.app.selection

import androidx.compose.ui.graphics.ImageBitmap
import com.copyeye.app.core.state.CopyEyeError
import com.copyeye.app.ocr.OcrResult
import com.copyeye.app.ocr.SmartAction

/** What the selection overlay is showing right now. */
sealed interface ScanUiState {

    /** The frame has arrived and recognition has been kicked off. */
    data class Scanning(val frame: ImageBitmap?) : ScanUiState

    /** Recognition finished with something to select. */
    data class Ready(
        val frame: ImageBitmap,
        val result: OcrResult,
        val selection: Selection,
        val mode: SelectionMode,
        val smartActions: List<SmartAction>,
        val editing: Boolean = false,
        val editedText: String = "",
        val regionMode: Boolean = false,
        val justCopied: Boolean = false,
    ) : ScanUiState {
        val hasSelection: Boolean get() = !selection.isEmpty
    }

    /** Recognition finished and found nothing. */
    data class NoText(val frame: ImageBitmap?) : ScanUiState

    /** The frame came back blank because the app underneath forbids capture. */
    data object SecureScreen : ScanUiState

    data class Failed(val error: CopyEyeError) : ScanUiState
}
