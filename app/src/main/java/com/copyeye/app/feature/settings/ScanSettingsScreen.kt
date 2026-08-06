package com.copyeye.app.feature.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copyeye.app.AppContainer
import com.copyeye.app.data.preferences.AppSettings
import com.copyeye.app.data.preferences.HighlightStyle
import com.copyeye.app.data.preferences.HistoryRetention
import com.copyeye.app.data.preferences.OcrMode
import com.copyeye.app.data.preferences.OcrScript
import com.copyeye.app.ui.components.DetailScaffold
import com.copyeye.app.ui.components.SettingsChoiceRow
import com.copyeye.app.ui.components.SettingsDivider
import com.copyeye.app.ui.components.SettingsMultiChoiceRow
import com.copyeye.app.ui.components.SettingsSection
import com.copyeye.app.ui.components.SettingsSliderRow
import com.copyeye.app.ui.components.SettingsSwitchRow
import com.copyeye.app.ui.components.asPercent
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ScanSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by container.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = AppSettings())
    val tier = container.deviceCapabilities.tier

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { container.settingsRepository.update(transform) }
    }

    DetailScaffold(title = "Scan settings", onBack = onBack) {

        SettingsSection(title = "Languages") {
            SettingsMultiChoiceRow(
                title = "Scripts to recognise",
                options = OcrScript.entries,
                selected = settings.scripts,
                labelOf = { script ->
                    when (script) {
                        OcrScript.Latin -> "English / Latin"
                        OcrScript.Devanagari -> "हिन्दी / Devanagari"
                    }
                },
                onToggle = { script ->
                    update { current ->
                        val next = if (script in current.scripts) {
                            current.scripts - script
                        } else {
                            current.scripts + script
                        }
                        // At least one script has to stay on or a scan can never return anything.
                        current.copy(scripts = next.ifEmpty { setOf(OcrScript.Latin) })
                    }
                },
            )
        }

        SettingsSection(title = "Speed and accuracy") {
            SettingsChoiceRow(
                title = "Mode",
                options = OcrMode.entries,
                selected = settings.ocrMode,
                labelOf = { mode ->
                    when (mode) {
                        OcrMode.Fast -> "Fast"
                        OcrMode.Accurate -> "Accurate"
                    }
                },
                onSelect = { mode -> update { it.copy(ocrMode = mode) } },
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = "Smart Frame Mode",
                subtitle = "Takes a few frames and keeps the sharpest one. Better for video, " +
                    "slower on older phones." +
                    if (container.deviceCapabilities.suggestsLowPerformanceMode) {
                        " Not recommended on this device."
                    } else {
                        ""
                    },
                checked = settings.smartFrameMode,
                onCheckedChange = { enabled -> update { it.copy(smartFrameMode = enabled) } },
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = "Low performance mode",
                subtitle = "Smaller images and no extra frames. Suggested tier: $tier",
                checked = settings.lowPerformanceMode,
                onCheckedChange = { enabled -> update { it.copy(lowPerformanceMode = enabled) } },
            )
        }

        SettingsSection(title = "After a scan") {
            SettingsSwitchRow(
                title = "Copy straight away when there is one line",
                subtitle = "Skips the selection screen when there is nothing to choose between",
                checked = settings.autoCopySingleLine,
                onCheckedChange = { enabled -> update { it.copy(autoCopySingleLine = enabled) } },
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = "Close after copying",
                checked = settings.closeAfterCopy,
                onCheckedChange = { enabled -> update { it.copy(closeAfterCopy = enabled) } },
            )
            SettingsDivider()
            SettingsSliderRow(
                title = "Close delay",
                valueLabel = "${settings.closeAfterCopyDelayMs} ms",
                value = settings.closeAfterCopyDelayMs.toFloat(),
                range = 0f..2000f,
                steps = 19,
                enabled = settings.closeAfterCopy,
                onValueChange = { value ->
                    update { it.copy(closeAfterCopyDelayMs = value.roundToInt().toLong()) }
                },
            )
        }

        SettingsSection(title = "Selection screen") {
            SettingsChoiceRow(
                title = "Highlight style",
                options = HighlightStyle.entries,
                selected = settings.highlightStyle,
                labelOf = { it.name },
                onSelect = { style -> update { it.copy(highlightStyle = style) } },
            )
            SettingsDivider()
            SettingsSliderRow(
                title = "Background dim",
                valueLabel = settings.backgroundDimAmount.asPercent(),
                value = settings.backgroundDimAmount,
                range = 0f..0.85f,
                onValueChange = { value -> update { it.copy(backgroundDimAmount = value) } },
            )
        }

        SettingsSection(title = "Clipboard history") {
            SettingsSwitchRow(
                title = "Keep what I copy",
                subtitle = "Off by default. Only text copied through CopyEye is stored, and only " +
                    "on this phone.",
                checked = settings.historyEnabled,
                onCheckedChange = { enabled -> update { it.copy(historyEnabled = enabled) } },
            )
            SettingsDivider()
            SettingsChoiceRow(
                title = "Delete after",
                options = HistoryRetention.entries,
                selected = settings.historyRetention,
                labelOf = { retention ->
                    when (retention) {
                        HistoryRetention.OneHour -> "1 hour"
                        HistoryRetention.OneDay -> "24 hours"
                        HistoryRetention.SevenDays -> "7 days"
                        HistoryRetention.Forever -> "Never"
                    }
                },
                onSelect = { retention -> update { it.copy(historyRetention = retention) } },
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}
