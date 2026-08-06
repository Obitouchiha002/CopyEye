package com.copyeye.app.capture

import android.graphics.Bitmap
import com.copyeye.app.core.state.CopyEyeError

/**
 * One captured screen image plus everything the OCR mapper needs to put its results back on top of
 * the real screen.
 *
 * [contentOffsetX]/[contentOffsetY] record how much was cropped off the top-left of the raw
 * capture, and [scale] how much it was shrunk, so a bounding box in bitmap space can be turned
 * back into a screen coordinate without the caller knowing what the pipeline did.
 */
class ScreenFrame(
    val bitmap: Bitmap,
    val screenWidth: Int,
    val screenHeight: Int,
    val contentOffsetX: Int = 0,
    val contentOffsetY: Int = 0,
    val scale: Float = 1f,
    val rotationDegrees: Int = 0,
) {
    val isRecycled: Boolean get() = bitmap.isRecycled

    /** Frees the pixel memory. Called as soon as a scan session ends — frames are never kept. */
    fun release() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

/** The result of asking for one frame. */
sealed interface CaptureOutcome {
    data class Success(val frame: ScreenFrame) : CaptureOutcome

    /** The compositor handed back a blank image, which is what FLAG_SECURE and DRM look like. */
    data object SecureContent : CaptureOutcome

    data class Failure(val error: CopyEyeError, val cause: Throwable? = null) : CaptureOutcome
}
