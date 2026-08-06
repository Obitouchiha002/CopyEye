package com.copyeye.app.ocr

import com.copyeye.app.capture.ScreenFrame
import com.copyeye.app.data.preferences.OcrScript

/**
 * Recognises text in a captured frame.
 *
 * An interface rather than a direct ML Kit call so the selection UI can be driven by a fake in
 * tests, and so a different on-device engine could be swapped in without touching anything above
 * this layer. Implementations must run off the main thread and must be cancellable: a scan that
 * the user has already dismissed has to stop burning CPU immediately.
 */
interface TextRecognitionEngine {

    /**
     * @param scripts which recognisers to run. Running more than one costs more CPU but the calls
     *   are concurrent, so wall-clock cost is roughly the slowest one rather than the sum.
     */
    suspend fun recognize(frame: ScreenFrame, scripts: Set<OcrScript>): OcrResult

    /** Releases native recogniser handles. */
    fun close()
}

/** Thrown when no recogniser could be created at all — a missing or corrupt bundled model. */
class OcrUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
