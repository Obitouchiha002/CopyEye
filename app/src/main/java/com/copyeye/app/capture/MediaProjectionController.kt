package com.copyeye.app.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.copyeye.app.core.common.ApiLevel
import com.copyeye.app.core.state.CopyEyeError
import com.copyeye.app.data.preferences.CaptureMethod
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * Owns the one and only [MediaProjection] session.
 *
 * ## Why the session is long-lived but the capture is not
 *
 * From Android 14, `MediaProjection.createVirtualDisplay` may be called exactly once per granted
 * projection, and a fresh grant needs a fresh consent dialog. Creating a display per scan is
 * therefore not an option: the user would see a system dialog on every single tap.
 *
 * So the virtual display is created once, when the user turns CopyEye on, and lives for the whole
 * session. What is *not* long-lived is the surface behind it. While idle the display renders to no
 * surface at all ([VirtualDisplay.setSurface] with `null`), which means the compositor produces no
 * frames and no screen content reaches this process. A scan attaches the [ImageReader]'s surface,
 * takes the first frame that arrives, and detaches again.
 *
 * That is what makes "CopyEye only looks when you tap it" a structural property rather than a
 * promise about how carefully the code avoids reading a buffer it is continuously being handed.
 */
class MediaProjectionController(private val context: Context) : ScreenCaptureSource {

    /** Raised when the system tears the projection down — user revoked, or another app took over. */
    fun interface SessionListener {
        fun onProjectionStopped()
    }

    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var readerThread: HandlerThread? = null
    private var readerHandler: Handler? = null

    private var displayWidth = 0
    private var displayHeight = 0
    private var displayDensity = 0

    private val captureInFlight = AtomicBoolean(false)
    private var listener: SessionListener? = null

    val isActive: Boolean get() = projection != null && virtualDisplay != null

    override val method: CaptureMethod = CaptureMethod.ScreenRecording

    override val isReady: Boolean get() = isActive

    /** Consent is asked for per session, so "setup" is whether a session is currently live. */
    override val needsSetup: Boolean get() = !isActive

    override suspend fun capture(timeoutMs: Long): CaptureOutcome = captureFrame(timeoutMs)

    override fun onDisplayChanged(width: Int, height: Int, densityDpi: Int) =
        resize(width, height, densityDpi)

    override fun release() = stop()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "Projection stopped by system")
            teardown()
            listener?.onProjectionStopped()
        }
    }

    fun setSessionListener(listener: SessionListener?) {
        this.listener = listener
    }

    /**
     * Builds the intent that asks the user for screen-capture consent. The result must be passed
     * straight to [start]; caching it and reusing it later throws on Android 14+.
     */
    fun createConsentIntent(): Intent = projectionManager.createScreenCaptureIntent()

    /**
     * Starts the session.
     *
     * On API 34+ the caller must already be running as a `mediaProjection` foreground service, or
     * this throws `SecurityException`.
     *
     * @return true when the session came up.
     */
    fun start(resultCode: Int, data: Intent, width: Int, height: Int, densityDpi: Int): Boolean {
        if (isActive) return true
        return try {
            val media = projectionManager.getMediaProjection(resultCode, data)
                ?: return false

            readerThread = HandlerThread("copyeye-capture").also { it.start() }
            readerHandler = Handler(readerThread!!.looper)

            media.registerCallback(projectionCallback, readerHandler)
            projection = media

            displayWidth = width
            displayHeight = height
            displayDensity = densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)

            // Created with a null surface: nothing is rendered until a scan asks for it.
            virtualDisplay = media.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                /* surface = */ null,
                /* callback = */ null,
                readerHandler,
            )
            virtualDisplay != null
        } catch (e: SecurityException) {
            Log.e(TAG, "Projection refused: ${e.message}")
            teardown()
            false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Projection could not start: ${e.message}")
            teardown()
            false
        }
    }

    /**
     * Follows a rotation or a split-screen resize.
     *
     * The display is resized rather than recreated, because recreating it would need a second
     * `createVirtualDisplay` call, which Android 14 forbids.
     */
    private fun resize(width: Int, height: Int, densityDpi: Int) {
        val display = virtualDisplay ?: return
        if (width == displayWidth && height == displayHeight) return
        if (width <= 0 || height <= 0) return
        try {
            display.setSurface(null)
            imageReader?.close()
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
            display.resize(width, height, densityDpi)
            displayWidth = width
            displayHeight = height
            displayDensity = densityDpi
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Resize failed: ${e.message}")
        }
    }

    /**
     * Grabs a single frame.
     *
     * Rejects re-entrant calls outright: several taps in quick succession must not queue several
     * captures, and the second tap is more likely to be an accident than an intent.
     */
    suspend fun captureFrame(timeoutMs: Long = CAPTURE_TIMEOUT_MS): CaptureOutcome {
        val display = virtualDisplay
        val reader = imageReader
        if (display == null || reader == null) {
            return CaptureOutcome.Failure(CopyEyeError.ProjectionStopped)
        }
        if (!captureInFlight.compareAndSet(false, true)) {
            return CaptureOutcome.Failure(CopyEyeError.CaptureTimeout)
        }
        return try {
            val bitmap = withTimeout(timeoutMs) { awaitFrame(display, reader) }
            when {
                bitmap == null -> CaptureOutcome.Failure(CopyEyeError.CaptureEmpty)
                FrameAnalysis.isBlank(bitmap) -> {
                    bitmap.recycle()
                    CaptureOutcome.SecureContent
                }
                else -> CaptureOutcome.Success(
                    ScreenFrame(
                        bitmap = bitmap,
                        screenWidth = displayWidth,
                        screenHeight = displayHeight,
                    ),
                )
            }
        } catch (e: TimeoutCancellationException) {
            CaptureOutcome.Failure(CopyEyeError.CaptureTimeout, e)
        } catch (e: OutOfMemoryError) {
            CaptureOutcome.Failure(CopyEyeError.LowMemory)
        } catch (e: IllegalStateException) {
            CaptureOutcome.Failure(CopyEyeError.ProjectionStopped, e)
        } finally {
            detachSurface(display, reader)
            captureInFlight.set(false)
        }
    }

    private suspend fun awaitFrame(display: VirtualDisplay, reader: ImageReader): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            val delivered = AtomicBoolean(false)
            reader.setOnImageAvailableListener({ source ->
                if (delivered.get()) {
                    // Drain and drop anything that arrives after we already have our frame.
                    source.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                var image: Image? = null
                try {
                    image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val bitmap = image.toBitmap()
                    if (delivered.compareAndSet(false, true)) {
                        continuation.resumeIfActive(bitmap)
                    } else {
                        bitmap?.recycle()
                    }
                } catch (e: Exception) {
                    if (delivered.compareAndSet(false, true)) continuation.resumeIfActive(null)
                    Log.w(TAG, "Frame conversion failed: ${e.message}")
                } finally {
                    image?.close()
                }
            }, readerHandler)

            continuation.invokeOnCancellation {
                delivered.set(true)
                reader.setOnImageAvailableListener(null, null)
            }

            // Only now does the compositor start producing frames for us.
            try {
                display.setSurface(reader.surface)
            } catch (e: IllegalStateException) {
                if (delivered.compareAndSet(false, true)) continuation.resumeIfActive(null)
            }
        }

    private fun CancellableContinuation<Bitmap?>.resumeIfActive(value: Bitmap?) {
        if (isActive) resume(value) else value?.recycle()
    }

    private fun detachSurface(display: VirtualDisplay, reader: ImageReader) {
        try {
            display.setSurface(null)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not detach surface: ${e.message}")
        }
        reader.setOnImageAvailableListener(null, null)
        // Drain anything the compositor produced between the last frame and the detach.
        while (true) {
            val leftover = try {
                reader.acquireNextImage()
            } catch (e: IllegalStateException) {
                null
            } ?: break
            leftover.close()
        }
    }

    private fun stop() {
        teardown()
    }

    private fun teardown() {
        try {
            virtualDisplay?.setSurface(null)
        } catch (e: IllegalStateException) {
            // Already gone.
        }
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        projection?.let {
            try {
                it.unregisterCallback(projectionCallback)
            } catch (e: IllegalStateException) {
                // The callback may already have been dropped by the framework.
            }
            it.stop()
        }
        projection = null

        readerThread?.quitSafely()
        readerThread = null
        readerHandler = null
    }

    /**
     * `ImageReader` hands back rows padded to a hardware-friendly stride, so a straight
     * `copyPixelsFromBuffer` produces a skewed image on most devices. The padded columns are
     * cropped off after the copy.
     */
    private fun Image.toBitmap(): Bitmap? {
        val planes = planes
        if (planes.isEmpty()) return null
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride.coerceAtLeast(1)

        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)

        if (rowPadding == 0) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        if (cropped !== padded) padded.recycle()
        return cropped
    }

    companion object {
        private const val TAG = "CopyEye/Projection"
        private const val VIRTUAL_DISPLAY_NAME = "CopyEye"
        private const val MAX_IMAGES = 2
        const val CAPTURE_TIMEOUT_MS = 2_500L

        /**
         * True when the platform allows exactly one `createVirtualDisplay` call per grant, which is
         * what forces the session to be long-lived. See docs/ARCHITECTURE.md.
         */
        val allowsOnlyOneVirtualDisplay: Boolean get() = ApiLevel.hasStrictMediaProjectionRules
    }
}
