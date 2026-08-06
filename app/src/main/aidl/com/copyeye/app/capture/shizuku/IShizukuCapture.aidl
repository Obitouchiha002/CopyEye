package com.copyeye.app.capture.shizuku;

/**
 * The slice of work that runs inside Shizuku's shell process.
 *
 * Deliberately tiny: one call that takes a screenshot and streams it back. Everything that could
 * be done in the app's own process is done there, because code running with shell privileges is
 * the last place that should be growing features.
 */
interface IShizukuCapture {

    /**
     * Runs `screencap -p` and returns the read end of a pipe carrying the PNG.
     *
     * A pipe rather than a byte array because a screenshot is several megabytes and a binder
     * transaction caps out around one.
     */
    ParcelFileDescriptor capture() = 1;

    /**
     * Shizuku's fixed transaction id for tearing a user service down. Because this one is pinned,
     * AIDL requires every method in the interface to carry an explicit id.
     */
    void destroy() = 16777114;
}
