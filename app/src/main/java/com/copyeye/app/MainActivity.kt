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
import com.copyeye.app.core.common.ApiLevel
import com.copyeye.app.overlay.FloatingEyeService
import com.copyeye.app.ui.nav.CopyEyeNavHost
import com.copyeye.app.ui.nav.Route
import com.copyeye.app.ui.theme.CopyEyeTheme

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

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            // The consent result goes straight to the service. On Android 14+ the foreground
            // service has to be the thing that calls getMediaProjection, and it must do so with a
            // result that has never been used before.
            val intent = FloatingEyeService.startIntent(this, result.resultCode, data)
            ContextCompat.startForegroundService(this, intent)
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

        setContent {
            CopyEyeTheme {
                CopyEyeNavHost(
                    container = container,
                    startRoute = routeFromIntent(intent),
                    onRequestOverlayPermission = ::requestOverlayPermission,
                    onRequestCapturePermission = ::requestCapturePermission,
                    onRequestNotificationPermission = ::requestNotificationPermission,
                    onStopService = ::stopFloatingEye,
                    onOpenSystemIntent = ::launchSystemIntent,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun requestOverlayPermission() {
        try {
            overlayLauncher.launch(container.permissionChecker.overlaySettingsIntent())
        } catch (e: Exception) {
            // A few OEM builds do not expose the per-app overlay page.
            launchSystemIntent(container.permissionChecker.appSettingsIntent())
        }
    }

    private fun requestCapturePermission() {
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
