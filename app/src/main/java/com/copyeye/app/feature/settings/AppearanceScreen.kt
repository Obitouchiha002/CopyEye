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
import com.copyeye.app.data.preferences.AnimationIntensity
import com.copyeye.app.data.preferences.AppSettings
import com.copyeye.app.data.preferences.EyeAccent
import com.copyeye.app.data.preferences.EyeStyle
import com.copyeye.app.data.preferences.PreferredEdge
import com.copyeye.app.data.preferences.SettingsRepository
import com.copyeye.app.ui.components.DetailScaffold
import com.copyeye.app.ui.components.SettingsChoiceRow
import com.copyeye.app.ui.components.SettingsDivider
import com.copyeye.app.ui.components.SettingsNavigationRow
import com.copyeye.app.ui.components.SettingsSection
import com.copyeye.app.ui.components.SettingsSliderRow
import com.copyeye.app.ui.components.SettingsSwitchRow
import com.copyeye.app.ui.components.asPercent
import com.copyeye.app.ui.nav.Route
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun AppearanceScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settings by container.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = AppSettings())

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { container.settingsRepository.update(transform) }
    }

    DetailScaffold(title = "Appearance", onBack = onBack) {

        SettingsSection(title = "Iris") {
            SettingsChoiceRow(
                title = "Eye style",
                options = EyeStyle.entries,
                selected = settings.eyeStyle,
                labelOf = { it.name },
                onSelect = { style -> update { it.copy(eyeStyle = style) } },
            )
            SettingsDivider()
            SettingsChoiceRow(
                title = "Colour",
                options = EyeAccent.entries,
                selected = settings.eyeAccent,
                labelOf = { it.name },
                onSelect = { accent -> update { it.copy(eyeAccent = accent) } },
            )
            SettingsDivider()
            SettingsSliderRow(
                title = "Size",
                valueLabel = "${settings.eyeSizeDp} dp",
                value = settings.eyeSizeDp.toFloat(),
                range = SettingsRepository.EYE_SIZE_MIN.toFloat()..SettingsRepository.EYE_SIZE_MAX.toFloat(),
                steps = SettingsRepository.EYE_SIZE_MAX - SettingsRepository.EYE_SIZE_MIN - 1,
                onValueChange = { value -> update { it.copy(eyeSizeDp = value.roundToInt()) } },
            )
            SettingsDivider()
            SettingsChoiceRow(
                title = "Preferred edge",
                options = PreferredEdge.entries,
                selected = settings.preferredEdge,
                labelOf = { it.name },
                onSelect = { edge -> update { it.copy(preferredEdge = edge) } },
            )
        }

        SettingsSection(title = "Getting out of the way") {
            SettingsSwitchRow(
                title = "Fade when idle",
                subtitle = "Iris dims and tucks behind the edge after a few seconds",
                checked = settings.autoHideEnabled,
                onCheckedChange = { enabled -> update { it.copy(autoHideEnabled = enabled) } },
            )
            SettingsDivider()
            SettingsSliderRow(
                title = "Fade after",
                valueLabel = "${settings.autoDimDelayMs / 1000} s",
                value = (settings.autoDimDelayMs / 1000).toFloat(),
                range = 1f..15f,
                steps = 13,
                enabled = settings.autoHideEnabled,
                onValueChange = { value ->
                    update { it.copy(autoDimDelayMs = value.roundToInt() * 1000L) }
                },
            )
            SettingsDivider()
            SettingsSliderRow(
                title = "Idle opacity",
                valueLabel = settings.idleOpacity.asPercent(),
                value = settings.idleOpacity,
                range = 0.05f..1f,
                enabled = settings.autoHideEnabled,
                onValueChange = { value -> update { it.copy(idleOpacity = value) } },
            )
            SettingsDivider()
            SettingsSliderRow(
                title = "Edge peek",
                valueLabel = settings.edgePeekFraction.asPercent(),
                value = settings.edgePeekFraction,
                range = 0f..0.5f,
                enabled = settings.autoHideEnabled,
                onValueChange = { value -> update { it.copy(edgePeekFraction = value) } },
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = "Move out of the way automatically",
                subtitle = "Steps aside for the keyboard and for split-screen changes",
                checked = settings.autoRepositionEnabled,
                onCheckedChange = { enabled -> update { it.copy(autoRepositionEnabled = enabled) } },
            )
        }

        SettingsSection(title = "Motion and feedback") {
            SettingsChoiceRow(
                title = "Animation",
                options = AnimationIntensity.entries,
                selected = settings.animationIntensity,
                labelOf = { it.name },
                onSelect = { intensity -> update { it.copy(animationIntensity = intensity) } },
            )
            SettingsDivider()
            SettingsSliderRow(
                title = "Blink every",
                valueLabel = "${settings.blinkIntervalSeconds} s",
                value = settings.blinkIntervalSeconds.toFloat(),
                range = 3f..20f,
                steps = 16,
                enabled = settings.animationIntensity != AnimationIntensity.Off,
                onValueChange = { value ->
                    update { it.copy(blinkIntervalSeconds = value.roundToInt()) }
                },
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = "Reduced motion",
                subtitle = "Turns off every idle animation and the scan sweep",
                checked = settings.reducedMotion,
                onCheckedChange = { enabled -> update { it.copy(reducedMotion = enabled) } },
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = "Haptic feedback",
                checked = settings.hapticsEnabled,
                onCheckedChange = { enabled -> update { it.copy(hapticsEnabled = enabled) } },
            )
        }

        SettingsSection(title = "More") {
            SettingsNavigationRow(
                title = "Scan settings",
                onClick = { onNavigate(Route.ScanSettings) },
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}
