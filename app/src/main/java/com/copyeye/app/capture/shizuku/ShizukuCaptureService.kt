package com.copyeye.app.capture.shizuku

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlin.concurrent.thread

/**
 * The part of CopyEye that runs inside Shizuku's shell process.
 *
 * This class has shell privileges. It is deliberately as small as it can be: one method, which runs
 * the platform's own `screencap` binary and pipes the result back. It parses nothing, keeps nothing,
 * writes nothing to disk, and holds no state between calls.
 *
 * Everything else — preprocessing, recognition, selection, the clipboard — happens in the app's
 * ordinary sandboxed process, where it belongs.
 *
 * Shizuku instantiates this reflectively, so the no-argument constructor must stay.
 */
class ShizukuCaptureService : IShizukuCapture.Stub() {

    /**
     * Streams a PNG of the current screen.
     *
     * A pipe rather than a returned byte array: a screenshot is several megabytes and a binder
     * transaction caps out near one. The write end is closed by the copying thread, which is what
     * signals end-of-stream to the reader.
     */
    override fun capture(): ParcelFileDescriptor? {
        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]

            thread(name = "copyeye-screencap") {
                var process: Process? = null
                try {
                    process = ProcessBuilder("screencap", "-p")
                        .redirectErrorStream(false)
                        .start()
                    ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                        process.inputStream.use { it.copyTo(out) }
                    }
                    process.waitFor()
                } catch (e: Exception) {
                    Log.w(TAG, "screencap failed: ${e.message}")
                    runCatching { writeEnd.close() }
                } finally {
                    process?.destroy()
                }
            }
            readEnd
        } catch (e: Exception) {
            Log.w(TAG, "Could not open a pipe: ${e.message}")
            null
        }
    }

    override fun destroy() {
        // Nothing is held open between calls, so there is nothing to unwind.
    }

    private companion object {
        const val TAG = "CopyEye/ShizukuSvc"
    }
}
