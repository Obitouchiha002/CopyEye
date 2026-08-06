package com.copyeye.app.core.common

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * Central place for every "which Android version am I on" question.
 *
 * CopyEye's floor is Android 10 (API 29). Everything above it is supported, and everything that
 * changed above it is gated here rather than at the call sites, so the version rules can be audited
 * in one place.
 *
 * Each property carries `@ChecksSdkIntAtLeast`, which is what lets lint see through the indirection
 * and keep verifying `NewApi` at every call site. Without it this file would hide the very mistakes
 * it exists to prevent.
 *
 * ## What API 29 already guarantees, so is not gated here
 *
 * `TYPE_APPLICATION_OVERLAY` (26), notification channels (26), `Settings.canDrawOverlays` (23),
 * `View.getRootWindowInsets` (23), `Context.getSystemService(Class)` (23), `Map.putIfAbsent` (24),
 * `stopForeground(int)` (24), display-cutout layout modes (28), `VibrationEffect.createPredefined`
 * (29) and `startForeground(id, notification, type)` (29) are all unconditionally available.
 */
object ApiLevel {

    val sdk: Int get() = Build.VERSION.SDK_INT

    /** `WindowManager.getCurrentWindowMetrics` — the cutout-aware size source, from R. */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val hasWindowMetrics: Boolean get() = sdk >= Build.VERSION_CODES.R

    /** Typed `WindowInsets.Type` queries, including the IME inset, arrived in R. */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val hasTypedWindowInsets: Boolean get() = sdk >= Build.VERSION_CODES.R

    /** `VibratorManager` replaced the `Vibrator` system service in S. */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    val hasVibratorManager: Boolean get() = sdk >= Build.VERSION_CODES.S

    /** Material You colour extraction, from S. */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    val hasDynamicColor: Boolean get() = sdk >= Build.VERSION_CODES.S

    /** `POST_NOTIFICATIONS` became a runtime permission in Tiramisu. */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    val needsPostNotificationsConsent: Boolean get() = sdk >= Build.VERSION_CODES.TIRAMISU

    /** Typed `Intent.getParcelableExtra(String, Class)`, from Tiramisu. */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    val hasTypedParcelableExtras: Boolean get() = sdk >= Build.VERSION_CODES.TIRAMISU

    /**
     * From API 34 a media-projection foreground service must already be running *before*
     * `MediaProjectionManager.getMediaProjection` is called, and the projection may back exactly
     * one `createVirtualDisplay` call.
     */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    val hasStrictMediaProjectionRules: Boolean get() = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` arrived in U.
     *
     * Below it there is no "running but holding nothing" service type, so the service simply stays
     * a `mediaProjection` service throughout. That is fine on those versions: only Android 14 and
     * above require the type to be live *before* `getMediaProjection`, and only there does the type
     * need to change mid-life.
     */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    val hasSpecialUseForegroundServiceType: Boolean get() = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** `Activity.overridePendingTransition` was replaced by `overrideActivityTransition` in U. */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    val hasActivityTransitionOverrides: Boolean get() = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}
