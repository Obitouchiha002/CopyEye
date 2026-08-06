package com.copyeye.app.capture.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.copyeye.app.capture.CaptureOutcome
import com.copyeye.app.capture.FrameAnalysis
import com.copyeye.app.capture.ScreenCaptureSource
import com.copyeye.app.capture.ScreenFrame
import com.copyeye.app.core.state.CopyEyeError
import com.copyeye.app.data.preferences.CaptureMethod
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/**
 * Captures the screen through Shizuku's shell service.
 *
 * ## What this buys, and what it costs
 *
 * Android's own screen-capture API raises a consent dialog per session and a screen-recording
 * indicator for as long as a session lives. Neither can be suppressed, and the user has to accept
 * one of them. This path sidesteps both, because it is not using `MediaProjection` at all — it runs
 * the platform's `screencap` binary with shell privileges that the *user* granted, once, outside
 * this app.
 *
 * The cost is real and is not hidden anywhere: Shizuku must be installed, and on an unrooted phone
 * it must be re-activated over wireless debugging after every reboot. That makes this a power-user
 * option, never a default.
 *
 * ## What it deliberately does not do
 *
 * It does not attempt to read `FLAG_SECURE` content. `screencap` run as shell has no
 * `CAPTURE_SECURE_VIDEO_OUTPUT` permission, so protected layers come back blanked exactly as they do
 * through `MediaProjection` — and [FrameAnalysis.isBlank] turns that into the same honest message.
 * Shell access is used to skip a consent dialog, not to see anything the user could not otherwise
 * screenshot themselves.
 */
class ShizukuCaptureSource(private val context: Context) : ScreenCaptureSource {

    override val method: CaptureMethod = CaptureMethod.Shizuku

    private var service: IShizukuCapture? = null
    private val binding = AtomicBoolean(false)

    private var displayWidth = 0
    private var displayHeight = 0

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuCaptureService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("capture")
        .debuggable(false)
        .version(SERVICE_VERSION)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let(IShizukuCapture.Stub::asInterface)
            binding.set(false)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding.set(false)
        }
    }

    /** True when Shizuku is installed and running. Says nothing about permission. */
    val isShizukuRunning: Boolean
        get() = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }

    val hasPermission: Boolean
        get() = try {
            isShizukuRunning &&
                !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }

    override val isReady: Boolean get() = hasPermission && service != null

    override val needsSetup: Boolean get() = !hasPermission

    /** Asks Shizuku for permission. The user answers in Shizuku's own dialog. */
    fun requestPermission(requestCode: Int) {
        if (!isShizukuRunning) return
        runCatching { Shizuku.requestPermission(requestCode) }
    }

    /** Binds the shell-side service. Safe to call repeatedly. */
    fun connect() {
        if (!hasPermission || service != null || !binding.compareAndSet(false, true)) return
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (e: Exception) {
            Log.w(TAG, "Could not bind the Shizuku service: ${e.message}")
            binding.set(false)
        }
    }

    override fun onDisplayChanged(width: Int, height: Int, densityDpi: Int) {
        displayWidth = width
        displayHeight = height
    }

    override suspend fun capture(timeoutMs: Long): CaptureOutcome {
        if (!hasPermission) return CaptureOutcome.Failure(CopyEyeError.ProjectionPermissionDenied)

        val remote = service ?: run {
            connect()
            awaitBinding(timeoutMs)
            service
        } ?: return CaptureOutcome.Failure(CopyEyeError.ProjectionStopped)

        return try {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) {
                    val descriptor: ParcelFileDescriptor = remote.capture()
                        ?: return@withContext CaptureOutcome.Failure(CopyEyeError.CaptureEmpty)

                    val bitmap = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                        BitmapFactory.decodeStream(stream)
                    } ?: return@withContext CaptureOutcome.Failure(CopyEyeError.CaptureEmpty)

                    if (FrameAnalysis.isBlank(bitmap)) {
                        bitmap.recycle()
                        CaptureOutcome.SecureContent
                    } else {
                        CaptureOutcome.Success(
                            ScreenFrame(
                                bitmap = bitmap,
                                screenWidth = bitmap.width,
                                screenHeight = bitmap.height,
                            ),
                        )
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            CaptureOutcome.Failure(CopyEyeError.CaptureTimeout, e)
        } catch (e: OutOfMemoryError) {
            CaptureOutcome.Failure(CopyEyeError.LowMemory)
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku capture failed: ${e.message}")
            CaptureOutcome.Failure(CopyEyeError.Unknown, e)
        }
    }

    /** Waits for [connect] to land, so the first scan after setup does not simply fail. */
    private suspend fun awaitBinding(timeoutMs: Long) {
        if (service != null) return
        runCatching {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : Runnable {
                        override fun run() {
                            if (service != null || !binding.get()) {
                                if (continuation.isActive) continuation.resume(Unit)
                            } else {
                                android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed(this, POLL_INTERVAL_MS)
                            }
                        }
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post(listener)
                }
            }
        }
    }

    override fun release() {
        val current = service
        service = null
        if (current != null) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        }
    }

    private companion object {
        const val TAG = "CopyEye/Shizuku"

        /** Bump when [ShizukuCaptureService] changes, so a stale shell process is replaced. */
        const val SERVICE_VERSION = 1

        const val POLL_INTERVAL_MS = 50L
    }
}
