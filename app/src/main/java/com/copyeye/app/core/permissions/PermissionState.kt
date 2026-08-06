package com.copyeye.app.core.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.copyeye.app.core.common.ApiLevel

/** Everything CopyEye needs, and whether it has it. */
data class PermissionSnapshot(
    val canDrawOverlays: Boolean,
    val canPostNotifications: Boolean,
    val hasProjectionSession: Boolean,
) {
    /** The eye cannot be shown at all without this one. */
    val blocksOverlay: Boolean get() = !canDrawOverlays

    /** Scanning needs a live projection session; the eye can be shown without it. */
    val blocksScanning: Boolean get() = !hasProjectionSession

    val isFullyReady: Boolean get() = canDrawOverlays && hasProjectionSession
}

/**
 * Reads permission state and builds the intents that fix it.
 *
 * Overlay consent is a settings screen rather than a dialog, and screen-capture consent is a
 * one-shot system dialog that cannot be re-requested silently. That asymmetry is why this returns
 * intents for the caller to launch instead of trying to request anything itself.
 */
class PermissionChecker(context: Context) {

    private val appContext = context.applicationContext

    fun snapshot(hasProjectionSession: Boolean): PermissionSnapshot = PermissionSnapshot(
        canDrawOverlays = canDrawOverlays(),
        canPostNotifications = canPostNotifications(),
        hasProjectionSession = hasProjectionSession,
    )

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(appContext)

    fun canPostNotifications(): Boolean = if (ApiLevel.needsPostNotificationsConsent) {
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    /**
     * Opens the "Display over other apps" settings page for this app.
     *
     * A few OEM builds do not carry the per-app page, so the caller should fall back to
     * [appSettingsIntent] if this cannot be resolved.
     */
    fun overlaySettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        "package:${appContext.packageName}".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appSettingsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "package:${appContext.packageName}".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The battery-optimisation exemption screen.
     *
     * CopyEye does not request the exemption programmatically — Play policy reserves that for a
     * short list of app types, and a screen-capture utility is not on it. The Help screen points
     * the user here instead, which is the supported route.
     */
    fun batteryOptimisationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
