package com.copyeye.app.core.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
     * True on the OEM skins that add a background-pop-up permission on top of Android's own
     * "display over other apps".
     *
     * Android has no API for reading that permission, so the only honest thing to do is name the
     * phones it exists on and walk the user to it. Getting this wrong in the harmless direction —
     * showing the step to someone who does not need it — costs one tap; getting it wrong the other
     * way leaves them with a button that does nothing.
     */
    val needsOemPopupPermission: Boolean
        get() = Build.MANUFACTURER.lowercase() in OEMS_WITH_POPUP_PERMISSION ||
            Build.BRAND.lowercase() in OEMS_WITH_POPUP_PERMISSION

    /** Human-readable path to that setting, for the OEM this phone actually is. */
    val oemPopupPermissionPath: String
        get() = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" ->
                "Settings → Apps → Manage apps → CopyEye → Other permissions → " +
                    "\"Display pop-up windows while running in background\""
            "oppo", "realme", "oneplus" ->
                "Settings → Apps → CopyEye → Allow floating windows / Auto-launch"
            "vivo", "iqoo" ->
                "Settings → Apps → CopyEye → Permissions → \"Display pop-up windows while running " +
                    "in the background\""
            else -> "Settings → Apps → CopyEye → Other permissions → background pop-up windows"
        }

    /**
     * Best effort at opening the OEM's own permission editor, falling back to the standard app
     * details page — which every device has, even if it takes the user one more tap from there.
     */
    fun oemPopupPermissionIntent(): Intent {
        val candidates = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                )
                .putExtra("extra_pkgname", appContext.packageName),
            Intent().setClassName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.floatwindow.FloatWindowListActivity",
            ),
            Intent().setClassName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.PurviewTabActivity",
            ),
        )
        val resolvable = candidates.firstOrNull { intent ->
            appContext.packageManager.resolveActivity(intent, 0) != null
        }
        return (resolvable ?: appSettingsIntent()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** MIUI and friends also gate service restarts behind an "autostart" toggle. */
    fun autostartIntent(): Intent? {
        val candidates = listOf(
            Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            Intent().setClassName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            ),
            Intent().setClassName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
        )
        return candidates
            .firstOrNull { appContext.packageManager.resolveActivity(it, 0) != null }
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

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

    private companion object {
        val OEMS_WITH_POPUP_PERMISSION = setOf(
            "xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "vivo", "iqoo",
        )
    }
}
