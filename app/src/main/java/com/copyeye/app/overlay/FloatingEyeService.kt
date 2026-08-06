package com.copyeye.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.copyeye.app.CopyEyeApp
import com.copyeye.app.MainActivity
import com.copyeye.app.R
import com.copyeye.app.capture.CaptureOutcome
import com.copyeye.app.capture.FrameProcessor
import com.copyeye.app.capture.FrameStore
import com.copyeye.app.capture.FrameAnalysis
import com.copyeye.app.capture.MediaProjectionController
import com.copyeye.app.capture.ScreenCaptureSource
import com.copyeye.app.capture.shizuku.ShizukuCaptureSource
import com.copyeye.app.core.common.ApiLevel
import com.copyeye.app.core.state.CopyEyeBus
import com.copyeye.app.core.state.CopyEyeError
import com.copyeye.app.core.state.CopyEyeEvent
import com.copyeye.app.core.state.CopyEyeState
import com.copyeye.app.core.state.ScanTiming
import com.copyeye.app.data.preferences.AppSettings
import com.copyeye.app.data.preferences.CaptureMethod
import com.copyeye.app.data.preferences.ProjectionIdleTimeout
import com.copyeye.app.selection.ScanActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The foreground service that owns the floating eye and the screen-capture session.
 *
 * ## What the service is for
 *
 * Two things Android will not let a plain object hold: a window drawn over other apps for hours,
 * and a `MediaProjection`. Both need a process the system knows about and a notification the user
 * can see and act on. Everything else — how the eye moves, how text is chosen — lives elsewhere.
 *
 * ## Ordering rules this class exists to obey
 *
 * On Android 14 and above the sequence is fixed and unforgiving: user consent, *then* a foreground
 * service already running with `mediaProjection` type, *then* `getMediaProjection`. Getting the
 * order wrong throws `SecurityException` at the last step. That is why the consent result arrives
 * here as a start command rather than being handled in an activity.
 */
class FloatingEyeService : LifecycleService(), FloatingEyeController.Callbacks {

    private lateinit var container: com.copyeye.app.AppContainer
    private lateinit var controller: FloatingEyeController
    private lateinit var projection: MediaProjectionController
    private lateinit var shizuku: ShizukuCaptureSource

    /**
     * Which source a scan should use right now.
     *
     * Shizuku wins when the user has actually set it up, because it costs them neither a dialog nor
     * a recording indicator. Everyone else — the overwhelming majority — gets the public API.
     */
    private fun activeSource(): ScreenCaptureSource = when (settings.captureMethod) {
        CaptureMethod.Shizuku -> shizuku
        CaptureMethod.ScreenRecording -> projection
        CaptureMethod.Automatic -> if (shizuku.isReady || shizuku.hasPermission) shizuku else projection
    }

    private var settings: AppSettings = AppSettings()
    private var scanJob: Job? = null
    private var hideJob: Job? = null
    private var idleStopJob: Job? = null
    private var currentForegroundType = 0
    private var paused = false

    override fun onCreate() {
        super.onCreate()
        container = (application as CopyEyeApp).container
        projection = container.projectionController
        shizuku = container.shizukuCaptureSource
        shizuku.connect()
        controller = FloatingEyeController(this, container.haptics, this)
        controller.setPositionPersister { x, y ->
            lifecycleScope.launch { container.settingsRepository.savePosition(x, y) }
        }

        projection.setSessionListener {
            // The user revoked capture from the system UI, or another app took the projection.
            CopyEyeBus.setProjectionActive(false)
            CopyEyeBus.emit(CopyEyeEvent.ProjectionLost)
            updateNotification()
        }

        container.settingsRepository.settings
            .onEach { updated ->
                settings = updated
                container.haptics.enabled = updated.hapticsEnabled
                controller.updateSettings(updated)
            }
            .launchIn(lifecycleScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_SCAN -> onScanRequested()
            ACTION_PAUSE -> onPauseRequested()
            ACTION_RESUME -> resume()
            ACTION_STOP -> onStopRequested()
            ACTION_NOTIFY_COPIED -> controller.showSuccess()
            else -> if (!CopyEyeBus.serviceRunning.value) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller.onConfigurationChanged(newConfig)
        // The projection's virtual display has to follow the rotation, and resize is the only way
        // to do that — Android 14 forbids creating a second display on the same projection.
        val metrics = currentDisplayMetrics()
        activeSource().onDisplayChanged(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    override fun onDestroy() {
        scanJob?.cancel()
        hideJob?.cancel()
        idleStopJob?.cancel()
        controller.detach()
        projection.release()
        shizuku.release()
        FrameStore.clear()
        CopyEyeBus.setServiceRunning(false)
        super.onDestroy()
    }

    // --- Start / stop --------------------------------------------------------------------------

    private fun handleStart(intent: Intent) {
        val hasGrant = intent.hasExtra(EXTRA_RESULT_DATA)
        // Switching CopyEye on gives the user a floating button and nothing else. No capture
        // session is created and no screen-recording indicator appears until they actually ask for
        // a scan by tapping it.
        startAsForeground(
            if (hasGrant) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                idleForegroundType()
            },
        )
        CopyEyeBus.setServiceRunning(true)

        if (!container.permissionChecker.canDrawOverlays()) {
            CopyEyeBus.setState(CopyEyeState.PermissionRequired)
            CopyEyeBus.emit(CopyEyeEvent.Failed(CopyEyeError.OverlayPermissionDenied))
            stopSelf()
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val data: Intent? = if (ApiLevel.hasTypedParcelableExtras) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Int.MIN_VALUE && data != null && !projection.isActive) {
            val metrics = currentDisplayMetrics()
            val started = projection.start(
                resultCode,
                data,
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
            )
            CopyEyeBus.setProjectionActive(started)
            if (!started) {
                CopyEyeBus.emit(CopyEyeEvent.Failed(CopyEyeError.ProjectionPermissionDenied))
            }
        }

        paused = false
        controller.attach(settings)
        controller.setEyeVisible(true)
        updateNotification()
        scheduleProjectionRelease()

        if (intent.getBooleanExtra(EXTRA_SCAN_AFTER_START, false) && projection.isActive) {
            startScan(regionMode = false)
        }

        // Load the OCR models now, while the user is still looking at the notification, rather
        // than on their first tap. Cold model initialisation costs several seconds and would
        // otherwise land on the one scan they judge the app by.
        lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            container.textRecognitionEngine.warmUp(settings.scripts)
            Log.i(TAG, "OCR warm-up took ${SystemClock.elapsedRealtime() - started}ms")
        }
    }

    /**
     * Releases the capture session once it has gone unused.
     *
     * Android shows a screen-recording indicator for as long as a session exists, and there is no
     * way to suppress it — nor should there be. The only honest way to get the indicator off the
     * user's status bar is to stop holding the thing it is reporting. The cost is a fresh consent
     * dialog on the next scan, which is why the timeout is a setting and why scanning again inside
     * the window is free.
     *
     * The eye and the service stay alive throughout; only the projection goes.
     */
    private fun scheduleProjectionRelease() {
        idleStopJob?.cancel()
        if (!projection.isActive) return
        val timeout = settings.projectionIdleTimeout.millis ?: return
        idleStopJob = lifecycleScope.launch {
            delay(timeout)
            if (projection.isActive) {
                Log.i(TAG, "Releasing screen access after ${timeout}ms idle")
                releaseScreenAccess()
            }
        }
    }

    private fun resume() {
        paused = false
        hideJob?.cancel()
        controller.setEyeVisible(true)
        controller.transitionTo(CopyEyeState.EyeIdle)
        updateNotification()
    }

    override fun onStopRequested() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onPauseRequested() {
        paused = true
        controller.setEyeVisible(false)
        controller.transitionTo(CopyEyeState.Paused)
        updateNotification()
    }

    override fun onHideForAnHour() {
        onPauseRequested()
        hideJob?.cancel()
        hideJob = lifecycleScope.launch {
            delay(HIDE_DURATION_MS)
            resume()
        }
    }

    // --- Scanning ------------------------------------------------------------------------------

    override fun onScanRequested() = startScan(regionMode = false)

    override fun onScanAreaRequested() = startScan(regionMode = true)

    private fun startScan(regionMode: Boolean) {
        // A scan already running is not replaced: the second tap is far more likely to be
        // impatience than a request for a different frame.
        if (scanJob?.isActive == true) return

        val source = activeSource()
        if (!source.isReady) {
            if (source is ShizukuCaptureSource) {
                // Shizuku is configured but not connected yet — bind and let this tap fall through
                // to the capture, which waits for the binding.
                source.connect()
            } else {
                // The screen-recording session was released. Ask for it back and run this scan as
                // soon as it is granted, so the user's tap still ends in a scan rather than in a
                // settings screen they then have to act on.
                startActivity(
                    MainActivity.reconnectIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return
            }
        }
        if (container.deviceCapabilities.isUnderMemoryPressure()) {
            CopyEyeBus.emit(CopyEyeEvent.Failed(CopyEyeError.LowMemory))
            controller.showBlocked()
            return
        }

        idleStopJob?.cancel()
        controller.transitionTo(CopyEyeState.Capturing)
        controller.setMood(com.copyeye.app.overlay.IrisEyeView.Mood.Scanning)

        scanJob = lifecycleScope.launch {
            try {
                val tapAt = SystemClock.elapsedRealtime()
                // Hiding the eye is not enough on its own — the compositor has to have drawn the
                // hide before the frame is grabbed, or Iris ends up inside her own screenshot.
                controller.hideEyeForCapture()
                val hiddenAt = SystemClock.elapsedRealtime()
                val outcome = if (settings.smartFrameMode && !settings.lowPerformanceMode) {
                    captureSharpest(source, SMART_FRAME_COUNT)
                } else {
                    source.capture()
                }
                val capturedAt = SystemClock.elapsedRealtime()
                controller.setEyeVisible(!paused)
                controller.setMood(com.copyeye.app.overlay.IrisEyeView.Mood.Idle)

                when (outcome) {
                    is CaptureOutcome.Success -> {
                        val prepared = FrameProcessor.prepare(
                            frame = outcome.frame,
                            mode = settings.ocrMode,
                            cropInsets = statusBarCrop(outcome.frame.screenHeight),
                            lowPerformanceMode = settings.lowPerformanceMode,
                        )
                        // Screen access is handed back the instant the pixels are in memory —
                        // before recognition, before the user has even chosen anything. From here
                        // on the scan is working on a bitmap, not on the screen.
                        // Shizuku holds nothing that raises an indicator, so there is nothing to
                        // give back; only the projection path needs releasing.
                        if (source is MediaProjectionController) {
                            if (settings.projectionIdleTimeout == ProjectionIdleTimeout.Immediately) {
                                releaseScreenAccess()
                            } else {
                                scheduleProjectionRelease()
                            }
                        }
                        FrameStore.put(prepared)
                        ScanTiming.record(
                            hideMs = hiddenAt - tapAt,
                            captureMs = capturedAt - hiddenAt,
                            prepareMs = SystemClock.elapsedRealtime() - capturedAt,
                        )
                        controller.transitionTo(CopyEyeState.Scanning)
                        launchScanScreen(
                            ScanActivity.intent(this@FloatingEyeService, regionMode),
                        )
                    }

                    CaptureOutcome.SecureContent -> {
                        controller.transitionTo(CopyEyeState.SecureScreen)
                        controller.showBlocked()
                        CopyEyeBus.emit(CopyEyeEvent.ScanBlockedBySecureScreen)
                        startActivity(
                            ScanActivity.secureScreenIntent(this@FloatingEyeService)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        controller.transitionTo(CopyEyeState.EyeIdle)
                    }

                    is CaptureOutcome.Failure -> {
                        controller.transitionTo(CopyEyeState.Error)
                        controller.showBlocked()
                        CopyEyeBus.emit(CopyEyeEvent.Failed(outcome.error))
                        controller.transitionTo(CopyEyeState.EyeIdle)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                controller.setEyeVisible(!paused)
                controller.setMood(com.copyeye.app.overlay.IrisEyeView.Mood.Idle)
                controller.transitionTo(CopyEyeState.EyeIdle)
                CopyEyeBus.emit(CopyEyeEvent.Failed(CopyEyeError.Unknown))
            }
        }
    }

    /**
     * Starts the selection screen and checks that it actually appeared.
     *
     * `startActivity` from a service is allowed on stock Android because CopyEye holds
     * `SYSTEM_ALERT_WINDOW`. Several OEM builds add a separate permission on top of that and, when
     * it is missing, drop the launch without raising anything. The tap simply does nothing — which
     * is indistinguishable from a broken app, and is exactly what gets reported as one.
     *
     * So the launch is followed by a check, and a launch that produced no screen is reported to the
     * user with the setting that fixes it.
     */
    private fun launchScanScreen(intent: Intent) {
        val requestedAt = SystemClock.elapsedRealtime()
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.e(TAG, "Scan screen refused outright: ${e.message}")
            reportScanScreenBlocked()
            return
        }
        lifecycleScope.launch {
            delay(SCAN_SCREEN_GRACE_MS)
            if (ScanActivity.lastStartedAtElapsedMs < requestedAt) {
                Log.e(TAG, "Scan screen never appeared — background launch was blocked")
                reportScanScreenBlocked()
            }
        }
    }

    private fun reportScanScreenBlocked() {
        FrameStore.clear()
        controller.showBlocked()
        controller.transitionTo(CopyEyeState.EyeIdle)
        CopyEyeBus.emit(CopyEyeEvent.Failed(CopyEyeError.ScanScreenBlocked))

        val manager = notificationManager() ?: return
        // High importance on purpose: this is the app telling the user why the thing they just
        // asked for did not happen, and it is useless if it is not seen.
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = getString(R.string.alert_channel_description) },
        )
        val open = PendingIntent.getActivity(
            this,
            1,
            container.permissionChecker.appSettingsIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_eye)
            .setContentTitle(getString(R.string.blocked_title))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.blocked_body)))
            .setContentText(getString(R.string.blocked_body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(ALERT_NOTIFICATION_ID, notification) }
    }

    /**
     * Takes a short burst and keeps the sharpest frame, for video where a single grab often lands on
     * a motion-blurred frame.
     */
    private suspend fun captureSharpest(source: ScreenCaptureSource, frames: Int): CaptureOutcome {
        var best: com.copyeye.app.capture.ScreenFrame? = null
        var bestScore = -1.0
        var lastFailure: CaptureOutcome = CaptureOutcome.Failure(CopyEyeError.CaptureEmpty)
        repeat(frames.coerceIn(1, 4)) {
            when (val outcome = source.capture()) {
                is CaptureOutcome.Success -> {
                    val score = FrameAnalysis.sharpness(outcome.frame.bitmap)
                    if (score > bestScore) {
                        best?.release()
                        best = outcome.frame
                        bestScore = score
                    } else {
                        outcome.frame.release()
                    }
                }
                // A blanked frame is conclusive; there is no point burning the rest of the burst.
                CaptureOutcome.SecureContent -> {
                    best?.release()
                    return CaptureOutcome.SecureContent
                }
                is CaptureOutcome.Failure -> lastFailure = outcome
            }
        }
        return best?.let { CaptureOutcome.Success(it) } ?: lastFailure
    }

    /**
     * The status bar carries a clock and icons, never text worth copying, and cropping it costs
     * nothing. The navigation bar is left alone: on gesture-navigation devices that region is real
     * app content.
     */
    private fun statusBarCrop(frameHeight: Int): Rect? {
        @Suppress("DiscouragedApi")
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id <= 0) return null
        val statusBar = resources.getDimensionPixelSize(id)
        if (statusBar <= 0 || statusBar >= frameHeight) return null
        return Rect(0, statusBar, Int.MAX_VALUE, frameHeight)
    }

    override fun onOpenHistory() {
        startActivity(MainActivity.historyIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onOpenSettings() {
        startActivity(MainActivity.settingsIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onStateChanged(state: CopyEyeState) {
        CopyEyeBus.setState(state)
    }

    private fun currentDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        if (ApiLevel.hasWindowMetrics) {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val display = (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        }
        return metrics
    }

    // --- Notification --------------------------------------------------------------------------

    /**
     * Runs the service in the foreground under the given type.
     *
     * The type is not fixed for the service's life. While idle, CopyEye holds no screen access at
     * all and runs as `specialUse` — which is why no screen-recording indicator appears. It becomes
     * a `mediaProjection` service only for the moment a scan is running, because Android requires
     * that type to already be active before `getMediaProjection` is called, and it drops straight
     * back afterwards.
     */
    /**
     * The type the service runs under when it is holding no screen access.
     *
     * `specialUse` only exists from Android 14. Below that there is no need for it either: the rule
     * that a `mediaProjection` service must be running before `getMediaProjection` is itself an
     * Android 14 rule, so on older versions the service can simply stay a `mediaProjection` service
     * for its whole life.
     */
    private fun idleForegroundType(): Int = if (ApiLevel.hasSpecialUseForegroundServiceType) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    }

    private fun startAsForeground(type: Int) {
        createChannel()
        val notification = buildNotification()
        try {
            startForeground(NOTIFICATION_ID, notification, type)
            currentForegroundType = type
            return
        } catch (e: SecurityException) {
            // A mediaProjection-typed start is refused without a live grant.
            Log.w(TAG, "Foreground type $type refused: ${e.message}")
        } catch (e: IllegalArgumentException) {
            // The type is not one this platform version knows, or is not declared in the manifest
            // as this version parses it.
            Log.w(TAG, "Foreground type $type rejected: ${e.message}")
        }

        // Whatever went wrong, the eye must survive: a service that cannot go foreground is a
        // service Android will kill, and the user would be left with nothing and no explanation.
        try {
            val fallback = idleForegroundType()
            if (fallback != type) {
                startForeground(NOTIFICATION_ID, notification, fallback)
                currentForegroundType = fallback
            } else {
                startForeground(NOTIFICATION_ID, notification)
                currentForegroundType = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not go foreground at all: ${e.message}")
            startForeground(NOTIFICATION_ID, notification)
            currentForegroundType = 0
        }
    }

    /** Drops back to the idle type once a scan no longer needs screen access. */
    private fun releaseScreenAccess() {
        idleStopJob?.cancel()
        if (projection.isActive) projection.release()
        CopyEyeBus.setProjectionActive(false)
        val idle = idleForegroundType()
        if (currentForegroundType != idle) startAsForeground(idle)
        updateNotification()
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        val manager = notificationManager() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            // Minimum importance keeps the notification out of the status bar entirely: it lives
            // collapsed at the bottom of the shade instead. Android will not let a foreground
            // service run without a notification at all — that is the deal for keeping the eye
            // alive for hours — but it does not have to occupy a slot the user looks at all day.
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val statusText = when {
            paused -> getString(R.string.notification_paused)
            projection.isActive -> getString(R.string.notification_ready)
            else -> getString(R.string.notification_no_capture)
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_eye)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(activityIntent())
            .addAction(action(R.string.notification_action_scan, ACTION_SCAN))
            .addAction(
                if (paused) {
                    action(R.string.notification_action_resume, ACTION_RESUME)
                } else {
                    action(R.string.notification_action_pause, ACTION_PAUSE)
                },
            )
            .addAction(action(R.string.notification_action_stop, ACTION_STOP))
            .build()
    }

    private fun updateNotification() {
        if (!CopyEyeBus.serviceRunning.value) return
        val manager = notificationManager() ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    /** Actions are text-only, so no icon is supplied. */
    private fun action(labelRes: Int, action: String): Notification.Action {
        val intent = PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, FloatingEyeService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null as Icon?, getString(labelRes), intent).build()
    }

    private fun activityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "CopyEye/Service"
        private const val CHANNEL_ID = "copyeye_service"
        private const val NOTIFICATION_ID = 4201
        private const val HIDE_DURATION_MS = 60L * 60L * 1000L
        private const val SMART_FRAME_COUNT = 3
        private const val ALERT_CHANNEL_ID = "copyeye_alerts"
        private const val ALERT_NOTIFICATION_ID = 4202

        /** How long to wait for the selection screen before deciding it was blocked. */
        private const val SCAN_SCREEN_GRACE_MS = 2_500L

        const val ACTION_START = "com.copyeye.app.action.START"
        const val ACTION_STOP = "com.copyeye.app.action.STOP"
        const val ACTION_SCAN = "com.copyeye.app.action.SCAN"
        const val ACTION_PAUSE = "com.copyeye.app.action.PAUSE"
        const val ACTION_RESUME = "com.copyeye.app.action.RESUME"
        const val ACTION_NOTIFY_COPIED = "com.copyeye.app.action.NOTIFY_COPIED"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SCAN_AFTER_START = "scan_after_start"

        /**
         * Start command carrying a fresh screen-capture consent result.
         *
         * @param scanImmediately true when this grant came from the user tapping Iris after the
         *   session had been released, so the tap should still end in a scan.
         */
        fun startIntent(
            context: Context,
            resultCode: Int,
            data: Intent,
            scanImmediately: Boolean = false,
        ): Intent =
            Intent(context, FloatingEyeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_SCAN_AFTER_START, scanImmediately)

        /** Starts the floating eye with no screen access at all. */
        fun eyeOnlyIntent(context: Context): Intent =
            Intent(context, FloatingEyeService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, FloatingEyeService::class.java).setAction(ACTION_STOP)

        fun copiedIntent(context: Context): Intent =
            Intent(context, FloatingEyeService::class.java).setAction(ACTION_NOTIFY_COPIED)
    }
}
