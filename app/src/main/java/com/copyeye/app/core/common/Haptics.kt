package com.copyeye.app.core.common

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** The four moments CopyEye speaks through touch. */
enum class Hap {
    /** Scan begins. */
    ScanStart,

    /** Text is ready to select. */
    TextReady,

    /** Something was copied. */
    Success,

    /** The screen could not be scanned. */
    Rejected,
}

/**
 * Short haptic pulses, gated on the user's preference.
 *
 * Predefined effects are used throughout rather than hand-rolled amplitudes, because OEMs tune
 * those to their own actuator; a duration that feels crisp on one phone buzzes on another.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = if (ApiLevel.hasVibratorManager) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    var enabled: Boolean = true

    fun play(hap: Hap) {
        if (!enabled) return
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        try {
            device.vibrate(effectFor(hap))
        } catch (e: SecurityException) {
            // VIBRATE is a normal permission, but some managed profiles still refuse it.
        }
    }

    private fun effectFor(hap: Hap): VibrationEffect = when (hap) {
        Hap.ScanStart -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        Hap.TextReady -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        Hap.Success -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        Hap.Rejected -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
    }
}
