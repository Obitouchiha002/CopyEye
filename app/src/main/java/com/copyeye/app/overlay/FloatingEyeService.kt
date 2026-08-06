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
import com.copyeye.app.capture.MediaProjectionController
import com.copyeye.app.core.common.ApiLevel
import com.copyeye.app.core.state.CopyEyeBus
import com.copyeye.app.core.state.CopyEyeError
import com.copyeye.app.core.state.CopyEyeEvent
import com.copyeye.app.core.state.CopyEyeState
import com.copyeye.app.data.preferences.AppSettings
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

    private var settings: AppSettings = AppSettings()
    private var scanJob: Job? = null
    private var hideJob: Job? = null
    private var paused = false

    override fun onCreate() {
        super.onCreate()
        container = (application as CopyEyeApp).container
        projection = container.projectionController
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
        projection.resize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    override fun onDestroy() {
        scanJob?.cancel()
        hideJob?.cancel()
        controller.detach()
        projection.stop()
        FrameStore.clear()
        CopyEyeBus.setServiceRunning(false)
        super.onDestroy()
    }

    // --- Start / stop --------------------------------------------------------------------------

    private fun handleStart(intent: Intent) {
        startAsForeground()
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

        if (!projection.isActive) {
            CopyEyeBus.emit(CopyEyeEvent.Failed(CopyEyeError.ProjectionStopped))
            controller.showBlocked()
            startActivity(
                MainActivity.reconnectIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        if (container.deviceCapabilities.isUnderMemoryPressure()) {
            CopyEyeBus.emit(CopyEyeEvent.Failed(CopyEyeError.LowMemory))
            controller.showBlocked()
            return
        }

        controller.transitionTo(CopyEyeState.Capturing)
        controller.setMood(com.copyeye.app.overlay.IrisEyeView.Mood.Scanning)
        // The eye is hidden for the grab so it does not end up inside its own screenshot.
        controller.setEyeVisible(false)

        scanJob = lifecycleScope.launch {
            try {
                val outcome = if (settings.smartFrameMode && !settings.lowPerformanceMode) {
                    projection.captureSharpestFrame(SMART_FRAME_COUNT)
                } else {
                    projection.captureFrame()
                }
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
                        FrameStore.put(prepared)
                        controller.transitionTo(CopyEyeState.Scanning)
                        startActivity(
                            ScanActivity.intent(this@FloatingEyeService, regionMode)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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

    private fun startAsForeground() {
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        val manager = notificationManager() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            // Low importance: the notification must be visible and actionable, never intrusive.
            NotificationManager.IMPORTANCE_LOW,
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

        const val ACTION_START = "com.copyeye.app.action.START"
        const val ACTION_STOP = "com.copyeye.app.action.STOP"
        const val ACTION_SCAN = "com.copyeye.app.action.SCAN"
        const val ACTION_PAUSE = "com.copyeye.app.action.PAUSE"
        const val ACTION_RESUME = "com.copyeye.app.action.RESUME"
        const val ACTION_NOTIFY_COPIED = "com.copyeye.app.action.NOTIFY_COPIED"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** Start command carrying a fresh screen-capture consent result. */
        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, FloatingEyeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun stopIntent(context: Context): Intent =
            Intent(context, FloatingEyeService::class.java).setAction(ACTION_STOP)

        fun copiedIntent(context: Context): Intent =
            Intent(context, FloatingEyeService::class.java).setAction(ACTION_NOTIFY_COPIED)
    }
}
