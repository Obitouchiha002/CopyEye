package com.copyeye.app.core.state

import android.util.Log

/**
 * Stage timings for one scan, carried from the service (which does the capture) to the selection
 * session (which does the recognition) so the whole tap-to-text budget can be logged in one line.
 *
 * Timings only — no screen content, no recognised text, ever. The log calls are `Log.d` and are
 * stripped from release builds by `-assumenosideeffects`.
 */
object ScanTiming {

    private const val TAG = "CopyEye/Timing"

    /** The product target: tap to selectable text, in milliseconds. */
    const val TARGET_TOTAL_MS = 1_000L

    @Volatile
    private var hideMs = 0L

    @Volatile
    private var captureMs = 0L

    @Volatile
    private var prepareMs = 0L

    fun record(hideMs: Long, captureMs: Long, prepareMs: Long) {
        this.hideMs = hideMs
        this.captureMs = captureMs
        this.prepareMs = prepareMs
    }

    /**
     * Called once recognition finishes. Logs the full breakdown and flags anything over target, so
     * a regression shows up as a warning rather than as a user noticing the app feels slow.
     */
    fun complete(ocrMs: Long, lineCount: Int, warm: Boolean) {
        val total = hideMs + captureMs + prepareMs + ocrMs
        val summary = "scan total=${total}ms " +
            "(hide=${hideMs} capture=${captureMs} prepare=${prepareMs} ocr=${ocrMs}) " +
            "lines=$lineCount warm=$warm"
        if (total > TARGET_TOTAL_MS) {
            Log.w(TAG, "OVER TARGET — $summary")
        } else {
            Log.d(TAG, summary)
        }
    }
}
