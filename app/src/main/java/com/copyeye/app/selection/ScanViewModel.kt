package com.copyeye.app.selection

import android.app.Application
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.copyeye.app.AppContainer
import com.copyeye.app.CopyEyeApp
import com.copyeye.app.capture.FrameStore
import com.copyeye.app.capture.ScreenFrame
import com.copyeye.app.core.common.Hap
import com.copyeye.app.core.state.CopyEyeError
import com.copyeye.app.core.state.CopyEyeBus
import com.copyeye.app.core.state.CopyEyeEvent
import com.copyeye.app.core.state.ScanTiming
import com.copyeye.app.data.preferences.AppSettings
import com.copyeye.app.ocr.OcrResult
import com.copyeye.app.ocr.OcrTimedOutException
import com.copyeye.app.ocr.OcrUnavailableException
import com.copyeye.app.ocr.SmartActionDetector
import com.copyeye.app.ocr.TextRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drives one scan session, from a captured frame to text on the clipboard.
 *
 * Owns the frame for exactly as long as the session lasts and destroys it in [onCleared] — the
 * activity being finished for any reason, including a rotation the user did mid-selection, is what
 * guarantees the pixels are gone.
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val container: AppContainer = (application as CopyEyeApp).container

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Scanning(null))
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private var frame: ScreenFrame? = null
    private var engine: SelectionEngine? = null
    private var recognitionJob: Job? = null
    private var settings: AppSettings = AppSettings()

    /** Recognition coordinates are in bitmap space; the UI works in the same space. */
    val bitmapWidth: Int get() = frame?.bitmap?.width ?: 0
    val bitmapHeight: Int get() = frame?.bitmap?.height ?: 0

    fun start(regionMode: Boolean, secureScreen: Boolean) {
        if (secureScreen) {
            _uiState.value = ScanUiState.SecureScreen
            return
        }
        recognitionJob?.cancel()
        recognitionJob = viewModelScope.launch {
            settings = container.settingsRepository.settings.first()
            container.haptics.enabled = settings.hapticsEnabled

            val captured = FrameStore.take()
            if (captured == null || captured.isRecycled) {
                _uiState.value = ScanUiState.Failed(CopyEyeError.CaptureEmpty)
                return@launch
            }
            frame = captured
            val image = captured.bitmap.asImageBitmap()
            _uiState.value = ScanUiState.Scanning(image)

            // Results arrive per script. The first emission ends the scanning animation, so the
            // user can start selecting English text while the Devanagari pass is still running.
            var emissions = 0
            try {
                container.textRecognitionEngine
                    .recognizeProgressive(captured, settings.scripts)
                    .collect { result ->
                        emissions++
                        if (result.isEmpty) return@collect

                        if (emissions == 1 || _uiState.value !is ScanUiState.Ready) {
                            ScanTiming.complete(
                                ocrMs = result.elapsedMs,
                                lineCount = result.lines.size,
                                warm = container.textRecognitionEngine.isWarm,
                            )
                            container.haptics.play(Hap.TextReady)
                        }
                        onResult(result, image, regionMode)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OcrTimedOutException) {
                _uiState.value = ScanUiState.Failed(CopyEyeError.OcrTimedOut)
                CopyEyeBus.emit(CopyEyeEvent.Failed(CopyEyeError.OcrTimedOut))
                return@launch
            } catch (e: OcrUnavailableException) {
                _uiState.value = ScanUiState.Failed(CopyEyeError.OcrUnavailable)
                CopyEyeBus.emit(CopyEyeEvent.Failed(CopyEyeError.OcrUnavailable))
                return@launch
            } catch (e: Exception) {
                _uiState.value = ScanUiState.Failed(CopyEyeError.OcrFailed)
                CopyEyeBus.emit(CopyEyeEvent.Failed(CopyEyeError.OcrFailed))
                return@launch
            }

            if (_uiState.value !is ScanUiState.Ready) {
                _uiState.value = ScanUiState.NoText(image)
                CopyEyeBus.emit(CopyEyeEvent.NoTextFound)
            }
        }
    }

    /**
     * Applies one recognition result.
     *
     * A later script adding lines must not disturb what the user has already chosen, so the
     * existing selection is carried across by re-resolving it against the new engine rather than
     * being reset.
     */
    private fun onResult(
        result: OcrResult,
        image: androidx.compose.ui.graphics.ImageBitmap,
        regionMode: Boolean,
    ) {
        val previous = _uiState.value as? ScanUiState.Ready
        val selectionEngine = SelectionEngine(result)
        engine = selectionEngine

        val selection = when {
            previous != null && !previous.selection.isEmpty ->
                // Word references are (lineId, index) pairs and line ids are stable within a scan,
                // so a selection made against the first result still resolves against the second.
                previous.selection
            settings.autoCopySingleLine && selectionEngine.hasSingleLine -> selectionEngine.selectAll()
            else -> Selection()
        }

        _uiState.value = ScanUiState.Ready(
            frame = image,
            result = result,
            selection = selection,
            mode = previous?.mode ?: SelectionMode.Line,
            smartActions = if (selection.isEmpty) {
                emptyList()
            } else {
                SmartActionDetector.detect(selectionEngine.textOf(selection))
            },
            regionMode = previous?.regionMode ?: regionMode,
            justCopied = previous?.justCopied ?: false,
        )

        if (previous == null && !selection.isEmpty && settings.autoCopySingleLine) {
            copySelection(closeAfter = true)
        }
    }

    // --- Selection -----------------------------------------------------------------------------

    fun onTap(x: Float, y: Float, tolerance: Float) = withReady { state, selectionEngine ->
        val updated = selectionEngine.selectAt(state.selection, x, y, tolerance, state.mode)
        publish(state, updated, selectionEngine)
    }

    fun onDragStart(x: Float, y: Float, tolerance: Float) = withReady { state, selectionEngine ->
        publish(state, selectionEngine.beginDrag(x, y, tolerance), selectionEngine)
    }

    fun onDragTo(x: Float, y: Float, tolerance: Float) = withReady { state, selectionEngine ->
        publish(state, selectionEngine.extendDrag(state.selection, x, y, tolerance), selectionEngine)
    }

    fun onRegionSelected(region: TextRect) = withReady { state, selectionEngine ->
        publish(state, selectionEngine.selectRegion(region), selectionEngine, regionMode = false)
    }

    fun setMode(mode: SelectionMode) = withReady { state, _ ->
        _uiState.value = state.copy(mode = mode)
    }

    fun setRegionMode(enabled: Boolean) = withReady { state, _ ->
        _uiState.value = state.copy(regionMode = enabled)
    }

    fun selectAll() = withReady { state, selectionEngine ->
        publish(state, selectionEngine.selectAll(), selectionEngine)
    }

    fun clearSelection() = withReady { state, selectionEngine ->
        publish(state, Selection(), selectionEngine)
    }

    /** Runs recognition again over just the selected region, at full frame resolution. */
    fun rescanRegion(region: TextRect) {
        val current = frame ?: return
        recognitionJob?.cancel()
        recognitionJob = viewModelScope.launch {
            val previous = _uiState.value
            _uiState.value = ScanUiState.Scanning((previous as? ScanUiState.Ready)?.frame)
            val result = try {
                container.textRecognitionEngine.recognize(current, settings.scripts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = previous
                return@launch
            }
            val limited = OcrResult(
                blocks = result.blocks.filter { it.box.intersects(region) },
                sourceWidth = result.sourceWidth,
                sourceHeight = result.sourceHeight,
                elapsedMs = result.elapsedMs,
            )
            if (limited.isEmpty) {
                _uiState.value = previous
                return@launch
            }
            val newEngine = SelectionEngine(limited)
            engine = newEngine
            _uiState.value = ScanUiState.Ready(
                frame = current.bitmap.asImageBitmap(),
                result = limited,
                selection = newEngine.selectAll(),
                mode = (previous as? ScanUiState.Ready)?.mode ?: SelectionMode.Line,
                smartActions = SmartActionDetector.detect(newEngine.textOf(newEngine.selectAll())),
            )
        }
    }

    // --- Editing -------------------------------------------------------------------------------

    fun beginEditing() = withReady { state, selectionEngine ->
        val text = if (state.hasSelection) {
            selectionEngine.textOf(state.selection)
        } else {
            state.result.fullText
        }
        _uiState.value = state.copy(editing = true, editedText = text)
    }

    fun onEditedTextChanged(text: String) = withReady { state, _ ->
        _uiState.value = state.copy(editedText = text)
    }

    fun cancelEditing() = withReady { state, _ ->
        _uiState.value = state.copy(editing = false)
    }

    // --- Copying -------------------------------------------------------------------------------

    /** @return true when the caller should close the overlay. */
    fun copySelection(closeAfter: Boolean = settings.closeAfterCopy): Boolean {
        val state = _uiState.value as? ScanUiState.Ready ?: return false
        val selectionEngine = engine ?: return false
        val text = when {
            state.editing -> state.editedText
            state.hasSelection -> selectionEngine.textOf(state.selection)
            else -> return false
        }
        return finishCopy(text, closeAfter)
    }

    fun copyAll(closeAfter: Boolean = settings.closeAfterCopy): Boolean {
        val state = _uiState.value as? ScanUiState.Ready ?: return false
        return finishCopy(state.result.fullText, closeAfter)
    }

    private fun finishCopy(text: String, closeAfter: Boolean): Boolean {
        if (text.isBlank()) return false
        val copied = container.clipboardWriter.copy(text)
        if (!copied) {
            CopyEyeBus.emit(CopyEyeEvent.Failed(CopyEyeError.ClipboardUnavailable))
            return false
        }
        container.haptics.play(Hap.Success)
        CopyEyeBus.emit(CopyEyeEvent.Copied(text.length))

        if (settings.historyEnabled) {
            viewModelScope.launch {
                container.historyRepository.add(text, settings.historyRetention)
            }
        }
        (_uiState.value as? ScanUiState.Ready)?.let {
            _uiState.value = it.copy(justCopied = true, editing = false)
        }
        return closeAfter
    }

    val closeDelayMs: Long get() = settings.closeAfterCopyDelayMs
    val dimAmount: Float get() = settings.backgroundDimAmount
    val highlightStyle get() = settings.highlightStyle
    val reducedMotion: Boolean get() = settings.reducedMotion || settings.motionSuppressed

    // --- Plumbing ------------------------------------------------------------------------------

    private inline fun withReady(block: (ScanUiState.Ready, SelectionEngine) -> Unit) {
        val state = _uiState.value as? ScanUiState.Ready ?: return
        val selectionEngine = engine ?: return
        block(state, selectionEngine)
    }

    private fun publish(
        state: ScanUiState.Ready,
        selection: Selection,
        selectionEngine: SelectionEngine,
        regionMode: Boolean = state.regionMode,
    ) {
        val text = selectionEngine.textOf(selection)
        _uiState.value = state.copy(
            selection = selection,
            smartActions = if (selection.isEmpty) emptyList() else SmartActionDetector.detect(text),
            regionMode = regionMode,
            justCopied = false,
        )
    }

    fun selectedText(): String {
        val state = _uiState.value as? ScanUiState.Ready ?: return ""
        val selectionEngine = engine ?: return ""
        return if (state.editing) state.editedText else selectionEngine.textOf(state.selection)
    }

    /**
     * True when what the user has chosen sits in the lower half of the frame.
     *
     * The toolbar uses it to move to the opposite end. Answering in *bitmap* space rather than
     * screen space keeps this out of the drawing layer entirely — the frozen frame is shown at 1:1
     * over the whole screen, so the two halves are the same halves.
     */
    fun selectionInLowerHalf(): Boolean {
        val rects = highlightRects()
        if (rects.isEmpty()) return false
        val height = frame?.bitmap?.height?.takeIf { it > 0 } ?: return false
        return rects.map { it.centerY }.average() > height / 2.0
    }

    fun highlightRects(): List<TextRect> {
        val state = _uiState.value as? ScanUiState.Ready ?: return emptyList()
        return engine?.highlightsFor(state.selection).orEmpty()
    }

    override fun onCleared() {
        recognitionJob?.cancel()
        // The captured frame dies with the session. Nothing is written to disk at any point.
        frame?.release()
        frame = null
        FrameStore.clear()
        super.onCleared()
    }
}
