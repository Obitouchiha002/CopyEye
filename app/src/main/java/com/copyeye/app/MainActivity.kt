package com.copyeye.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.copyeye.app.core.common.ApiLevel
import com.copyeye.app.data.preferences.ProjectionIdleTimeout
import com.copyeye.app.overlay.FloatingEyeService
import com.copyeye.app.ui.nav.CopyEyeNavHost
import com.copyeye.app.ui.nav.Route
import com.copyeye.app.ui.theme.CopyEyeTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The app's own window: onboarding, the on/off switch, settings, history and help.
 *
 * It also owns every permission handshake, because both of the ones that matter — the overlay
 * settings page and the screen-capture dialog — need an activity to return a result to. The service
 * can only ask for them by bouncing back through here.
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as CopyEyeApp).container

    /** True when this consent request came from a tap on Iris after the session had lapsed. */
    private var scanAfterGrant = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        val reconnecting = scanAfterGrant
        scanAfterGrant = false
        if (result.resultCode == RESULT_OK && data != null) {
            // The consent result goes straight to the service. On Android 14+ the foreground
            // service has to be the thing that calls getMediaProjection, and it must do so with a
            // result that has never been used before.
            val intent = FloatingEyeService.startIntent(
                context = this,
                resultCode = result.resultCode,
                data = data,
                scanImmediately = reconnecting,
            )
            ContextCompat.startForegroundService(this, intent)
            // A reconnect is a detour the user did not ask for, so get out of their way and let
            // the scan land on whatever they were actually looking at.
            if (reconnecting) finish()
        } else {
            toast(getString(R.string.error_capture_denied))
        }
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (!container.permissionChecker.canDrawOverlays()) {
            toast(getString(R.string.error_overlay_denied))
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) toast(getString(R.string.error_notifications_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start loading the OCR models the moment the app is opened, not when the service starts.
        // Model initialisation is by far the largest single cost in a cold scan, and the user is
        // about to spend several seconds granting two permissions — which is free time to spend on
        // it. By the time they first tap Iris the engine is usually already warm.
        lifecycleScope.launch {
            val scripts = container.settingsRepository.settings.first().scripts
            container.textRecognitionEngine.warmUp(scripts)
        }

        setContent {
            CopyEyeTheme {
                CopyEyeNavHost(
                    container = container,
                    startRoute = routeFromIntent(intent),
                    onRequestOverlayPermission = ::requestOverlayPermission,
                    onRequestCapturePermission = ::requestCapturePermission,
                    onRequestNotificationPermission = ::requestNotificationPermission,
                    onStartService = ::startFloatingEye,
                    onStopService = ::stopFloatingEye,
                    onOpenSystemIntent = ::launchSystemIntent,
                )
            }
        }

        maybeReconnect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeReconnect(intent)
    }

    /**
     * Tapping Iris after the idle session was released routes here. The dialog is raised straight
     * away rather than showing Home first — the user has already expressed what they want by
     * tapping, and asking them to press a second button would make the trade-off feel like a bug.
     */
    private fun maybeReconnect(intent: Intent?) {
        if (intent?.action != ACTION_RECONNECT) return
        intent.action = null
        requestCapturePermission(scanImmediately = true)
    }

    private fun requestOverlayPermission() {
        try {
            overlayLauncher.launch(container.permissionChecker.overlaySettingsIntent())
        } catch (e: Exception) {
            // A few OEM builds do not expose the per-app overlay page.
            launchSystemIntent(container.permissionChecker.appSettingsIntent())
        }
    }

    private fun requestCapturePermission(scanImmediately: Boolean = false) {
        scanAfterGrant = scanImmediately
        if (!container.permissionChecker.canDrawOverlays()) {
            requestOverlayPermission()
            return
        }
        requestNotificationPermission()
        try {
            projectionLauncher.launch(container.projectionController.createConsentIntent())
        } catch (e: Exception) {
            toast(getString(R.string.error_capture_unavailable))
        }
    }

    private fun requestNotificationPermission() {
        if (!ApiLevel.needsPostNotificationsConsent) return
        if (container.permissionChecker.canPostNotifications()) return
        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Puts the eye on screen.
     *
     * When the user has chosen to keep the capture session (the default), consent is asked for here
     * — once, at the moment they switch CopyEye on — so that every later tap scans instantly. When
     * they have chosen to release it after each scan, the eye starts with no screen access at all
     * and the dialog waits until they actually ask for a scan.
     */
    private fun startFloatingEye() {
        requestNotificationPermission()
        lifecycleScope.launch {
            val keepsSession =
                container.settingsRepository.settings.first().projectionIdleTimeout ==
                    ProjectionIdleTimeout.Never
            if (keepsSession) {
                requestCapturePermission()
            } else {
                ContextCompat.startForegroundService(this@MainActivity, FloatingEyeService.eyeOnlyIntent(this@MainActivity))
            }
        }
    }

    private fun stopFloatingEye() {
        runCatching { startService(FloatingEyeService.stopIntent(this)) }
    }

    private fun launchSystemIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.error_no_settings_screen))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun routeFromIntent(intent: Intent?): String? = when (intent?.action) {
        ACTION_OPEN_HISTORY -> Route.History.path
        ACTION_OPEN_SETTINGS -> Route.Appearance.path
        ACTION_RECONNECT -> Route.Home.path
        else -> null
    }

    companion object {
        private const val ACTION_OPEN_HISTORY = "com.copyeye.app.action.OPEN_HISTORY"
        private const val ACTION_OPEN_SETTINGS = "com.copyeye.app.action.OPEN_SETTINGS"
        private const val ACTION_RECONNECT = "com.copyeye.app.action.RECONNECT"

        fun historyIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_HISTORY)

        fun settingsIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_SETTINGS)

        /** Opened when a scan is attempted after the projection session has been torn down. */
        fun reconnectIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).setAction(ACTION_RECONNECT)
    }
}
