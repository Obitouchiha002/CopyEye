package com.copyeye.app.core.common

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings

/** Rough performance bracket, used to pick defaults rather than to block features. */
enum class DeviceTier { Low, Mid, High }

/**
 * Device facts that change what CopyEye does by default.
 *
 * Nothing here is a hard gate. A low-tier phone can still turn Smart Frame Mode on; it just does
 * not get it for free, because on a device with 2 GB of RAM a four-frame burst is the difference
 * between a scan and a stutter.
 */
class DeviceCapabilities(context: Context) {

    private val appContext = context.applicationContext

    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    val isLowRamDevice: Boolean =
        activityManager?.isLowRamDevice ?: false

    val totalMemoryMb: Long = run {
        val info = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(info)
        info.totalMem / (1024L * 1024L)
    }

    val tier: DeviceTier = when {
        isLowRamDevice || totalMemoryMb in 1..2_600 -> DeviceTier.Low
        totalMemoryMb in 2_601..5_200 -> DeviceTier.Mid
        totalMemoryMb > 5_200 -> DeviceTier.High
        // An unreadable memory figure on an old device is far more likely to be low-end.
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O -> DeviceTier.Low
        else -> DeviceTier.Mid
    }

    /** True when the memory pressure is high enough that a scan should be refused. */
    fun isUnderMemoryPressure(): Boolean {
        val info = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(info) ?: return false
        return info.lowMemory
    }

    /**
     * True when the user has turned animations off in Developer options or Accessibility.
     *
     * Respecting this is not optional: an overlay that keeps animating after the user asked the
     * whole system to stop is exactly the kind of thing that gets an app uninstalled.
     */
    fun systemAnimationsDisabled(): Boolean = try {
        val scale = Settings.Global.getFloat(
            appContext.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale == 0f
    } catch (e: Exception) {
        false
    }

    /** Sensible defaults for a fresh install on this device. */
    val suggestsLowPerformanceMode: Boolean get() = tier == DeviceTier.Low
    val suggestsSmartFrameMode: Boolean get() = tier == DeviceTier.High
}
