package com.copyeye.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "copyeye_settings",
)

/**
 * Reads and writes [AppSettings].
 *
 * Unknown or corrupt enum values fall back to the default rather than throwing: a preference file
 * written by a newer build must never crash an older one, and the overlay service has no user
 * interface in which to report a settings failure.
 */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = store.data
        .catch { cause ->
            // A read failure here is nearly always a partially written file after a crash.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map(::toSettings)

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val updated = transform(toSettings(prefs))
            write(prefs, updated)
        }
    }

    /** Position is written on every drag release, so it gets a narrow write path of its own. */
    suspend fun savePosition(xFraction: Float, yFraction: Float) {
        store.edit { prefs ->
            prefs[Keys.posX] = xFraction.coerceIn(0f, 1f)
            prefs[Keys.posY] = yFraction.coerceIn(0f, 1f)
        }
    }

    private object Keys {
        val eyeStyle = stringPreferencesKey("eye_style")
        val eyeSize = intPreferencesKey("eye_size_dp")
        val eyeAccent = stringPreferencesKey("eye_accent")
        val preferredEdge = stringPreferencesKey("preferred_edge")
        val animationIntensity = stringPreferencesKey("animation_intensity")
        val blinkSeconds = intPreferencesKey("blink_interval_seconds")
        val haptics = booleanPreferencesKey("haptics_enabled")
        val reducedMotion = booleanPreferencesKey("reduced_motion")

        val autoHide = booleanPreferencesKey("auto_hide_enabled")
        val dimDelay = longPreferencesKey("auto_dim_delay_ms")
        val idleOpacity = floatPreferencesKey("idle_opacity")
        val edgePeek = floatPreferencesKey("edge_peek_fraction")
        val autoReposition = booleanPreferencesKey("auto_reposition_enabled")

        val scripts = stringSetPreferencesKey("ocr_scripts")
        val ocrMode = stringPreferencesKey("ocr_mode")
        val smartFrame = booleanPreferencesKey("smart_frame_mode")
        val projectionIdle = stringPreferencesKey("projection_idle_timeout")
        val autoCopySingle = booleanPreferencesKey("auto_copy_single_line")
        val closeAfterCopy = booleanPreferencesKey("close_after_copy")
        val closeDelay = longPreferencesKey("close_after_copy_delay_ms")
        val highlightStyle = stringPreferencesKey("highlight_style")
        val dimAmount = floatPreferencesKey("background_dim_amount")

        val historyEnabled = booleanPreferencesKey("history_enabled")
        val historyRetention = stringPreferencesKey("history_retention")

        val lowPerf = booleanPreferencesKey("low_performance_mode")
        val onboarding = booleanPreferencesKey("onboarding_complete")
        val posX = floatPreferencesKey("eye_pos_x_fraction")
        val posY = floatPreferencesKey("eye_pos_y_fraction")
    }

    private fun toSettings(prefs: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            eyeStyle = prefs[Keys.eyeStyle].toEnum(defaults.eyeStyle),
            eyeSizeDp = prefs[Keys.eyeSize]?.coerceIn(EYE_SIZE_MIN, EYE_SIZE_MAX) ?: defaults.eyeSizeDp,
            eyeAccent = prefs[Keys.eyeAccent].toEnum(defaults.eyeAccent),
            preferredEdge = prefs[Keys.preferredEdge].toEnum(defaults.preferredEdge),
            animationIntensity = prefs[Keys.animationIntensity].toEnum(defaults.animationIntensity),
            blinkIntervalSeconds = prefs[Keys.blinkSeconds]?.coerceIn(3, 20) ?: defaults.blinkIntervalSeconds,
            hapticsEnabled = prefs[Keys.haptics] ?: defaults.hapticsEnabled,
            reducedMotion = prefs[Keys.reducedMotion] ?: defaults.reducedMotion,

            autoHideEnabled = prefs[Keys.autoHide] ?: defaults.autoHideEnabled,
            autoDimDelayMs = prefs[Keys.dimDelay]?.coerceIn(1_000L, 30_000L) ?: defaults.autoDimDelayMs,
            idleOpacity = prefs[Keys.idleOpacity]?.coerceIn(0.05f, 1f) ?: defaults.idleOpacity,
            edgePeekFraction = prefs[Keys.edgePeek]?.coerceIn(0f, 0.5f) ?: defaults.edgePeekFraction,
            autoRepositionEnabled = prefs[Keys.autoReposition] ?: defaults.autoRepositionEnabled,

            scripts = prefs[Keys.scripts]
                ?.mapNotNull { name -> runCatching { OcrScript.valueOf(name) }.getOrNull() }
                ?.toSet()
                ?.ifEmpty { defaults.scripts }
                ?: defaults.scripts,
            ocrMode = prefs[Keys.ocrMode].toEnum(defaults.ocrMode),
            smartFrameMode = prefs[Keys.smartFrame] ?: defaults.smartFrameMode,
            projectionIdleTimeout = prefs[Keys.projectionIdle].toEnum(defaults.projectionIdleTimeout),
            autoCopySingleLine = prefs[Keys.autoCopySingle] ?: defaults.autoCopySingleLine,
            closeAfterCopy = prefs[Keys.closeAfterCopy] ?: defaults.closeAfterCopy,
            closeAfterCopyDelayMs = prefs[Keys.closeDelay]?.coerceIn(0L, 5_000L) ?: defaults.closeAfterCopyDelayMs,
            highlightStyle = prefs[Keys.highlightStyle].toEnum(defaults.highlightStyle),
            backgroundDimAmount = prefs[Keys.dimAmount]?.coerceIn(0f, 0.85f) ?: defaults.backgroundDimAmount,

            historyEnabled = prefs[Keys.historyEnabled] ?: defaults.historyEnabled,
            historyRetention = prefs[Keys.historyRetention].toEnum(defaults.historyRetention),

            lowPerformanceMode = prefs[Keys.lowPerf] ?: defaults.lowPerformanceMode,
            onboardingComplete = prefs[Keys.onboarding] ?: defaults.onboardingComplete,
            eyePositionXFraction = prefs[Keys.posX]?.coerceIn(0f, 1f) ?: defaults.eyePositionXFraction,
            eyePositionYFraction = prefs[Keys.posY]?.coerceIn(0f, 1f) ?: defaults.eyePositionYFraction,
        )
    }

    private fun write(prefs: androidx.datastore.preferences.core.MutablePreferences, s: AppSettings) {
        prefs[Keys.eyeStyle] = s.eyeStyle.name
        prefs[Keys.eyeSize] = s.eyeSizeDp
        prefs[Keys.eyeAccent] = s.eyeAccent.name
        prefs[Keys.preferredEdge] = s.preferredEdge.name
        prefs[Keys.animationIntensity] = s.animationIntensity.name
        prefs[Keys.blinkSeconds] = s.blinkIntervalSeconds
        prefs[Keys.haptics] = s.hapticsEnabled
        prefs[Keys.reducedMotion] = s.reducedMotion

        prefs[Keys.autoHide] = s.autoHideEnabled
        prefs[Keys.dimDelay] = s.autoDimDelayMs
        prefs[Keys.idleOpacity] = s.idleOpacity
        prefs[Keys.edgePeek] = s.edgePeekFraction
        prefs[Keys.autoReposition] = s.autoRepositionEnabled

        prefs[Keys.scripts] = s.scripts.map { it.name }.toSet()
        prefs[Keys.ocrMode] = s.ocrMode.name
        prefs[Keys.smartFrame] = s.smartFrameMode
        prefs[Keys.projectionIdle] = s.projectionIdleTimeout.name
        prefs[Keys.autoCopySingle] = s.autoCopySingleLine
        prefs[Keys.closeAfterCopy] = s.closeAfterCopy
        prefs[Keys.closeDelay] = s.closeAfterCopyDelayMs
        prefs[Keys.highlightStyle] = s.highlightStyle.name
        prefs[Keys.dimAmount] = s.backgroundDimAmount

        prefs[Keys.historyEnabled] = s.historyEnabled
        prefs[Keys.historyRetention] = s.historyRetention.name

        prefs[Keys.lowPerf] = s.lowPerformanceMode
        prefs[Keys.onboarding] = s.onboardingComplete
        prefs[Keys.posX] = s.eyePositionXFraction
        prefs[Keys.posY] = s.eyePositionYFraction
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(fallback: E): E =
        this?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: fallback

    companion object {
        const val EYE_SIZE_MIN = 32
        const val EYE_SIZE_MAX = 56
    }
}
