package com.copyeye.app.ocr

import com.copyeye.app.capture.ScreenFrame
import com.copyeye.app.data.preferences.OcrScript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.last

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
     * True once the native models are loaded and a scan will not pay for loading them.
     *
     * Worth exposing because the difference is not subtle: the first recognition on a cold engine
     * includes model initialisation, which dominates everything else in the scan budget.
     */
    val isWarm: Boolean

    /**
     * Loads the models without any user waiting on the result.
     *
     * ML Kit initialises lazily on the first `process` call, which would otherwise land on the
     * user's first tap — the one moment they are judging whether the app feels fast. Called when
     * the service starts, so the cost is paid while they are still reading the notification.
     */
    suspend fun warmUp(scripts: Set<OcrScript>)

    /**
     * Recognises with each requested script in turn, emitting the merged result so far after each.
     *
     * ## Why this is a Flow
     *
     * ML Kit runs every recognition on one shared internal worker, so two scripts cost the *sum* of
     * their times, not the maximum — measured on a device, not assumed. With both Latin and
     * Devanagari enabled, waiting for everything before showing anything doubles the time the user
     * spends looking at a scanning animation.
     *
     * Emitting after each script instead means English text is selectable while the Devanagari pass
     * is still running, and the Hindi lines appear underneath the user's finger as they arrive. The
     * total is unchanged; the wait is halved.
     *
     * @param scripts which recognisers to run, in the order given.
     */
    fun recognizeProgressive(frame: ScreenFrame, scripts: Set<OcrScript>): Flow<OcrResult>

    /** Convenience for callers that genuinely need everything before proceeding, such as tests. */
    suspend fun recognize(frame: ScreenFrame, scripts: Set<OcrScript>): OcrResult =
        recognizeProgressive(frame, scripts).last()

    /** Releases native recogniser handles. */
    fun close()
}

/** Thrown when no recogniser could be created at all — a missing or corrupt bundled model. */
class OcrUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
