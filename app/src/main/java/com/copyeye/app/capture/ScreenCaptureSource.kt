package com.copyeye.app.capture

import com.copyeye.app.data.preferences.CaptureMethod

/**
 * Something that can hand back one image of the current screen.
 *
 * Two implementations exist because Android offers two genuinely different bargains, and neither is
 * right for everybody:
 *
 *  - [MediaProjectionController] uses the public `MediaProjection` API. It works on every device
 *    with no setup, and it costs either a consent dialog per scan or a permanent screen-recording
 *    indicator — the user picks which.
 *  - `ShizukuCaptureSource` runs `screencap` through Shizuku's shell service. No dialog and no
 *    indicator, ever, at the cost of a one-time pairing the user must redo after each reboot unless
 *    their device is rooted.
 *
 * Neither one attempts to defeat `FLAG_SECURE`. Both hand back a blanked frame for protected
 * screens, and [FrameAnalysis.isBlank] turns that into an honest message.
 */
interface ScreenCaptureSource {

    /** Which method this is, for the settings UI and for diagnostics. */
    val method: CaptureMethod

    /** True when this source could take a frame right now without further setup. */
    val isReady: Boolean

    /** True when the user has to be sent somewhere before this source can be used. */
    val needsSetup: Boolean

    /** Grabs a single frame. */
    suspend fun capture(timeoutMs: Long = DEFAULT_TIMEOUT_MS): CaptureOutcome

    /** Follows a rotation or resize. Sources that read the live display can ignore this. */
    fun onDisplayChanged(width: Int, height: Int, densityDpi: Int) = Unit

    /** Releases whatever the source is holding. */
    fun release()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_500L
    }
}
