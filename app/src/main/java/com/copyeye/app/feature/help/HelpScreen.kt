package com.copyeye.app.feature.help

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.copyeye.app.AppContainer
import com.copyeye.app.ui.components.DetailScaffold
import com.copyeye.app.ui.components.SettingsDivider
import com.copyeye.app.ui.components.SettingsNavigationRow
import com.copyeye.app.ui.components.SettingsSection

private data class HelpTopic(val question: String, val answer: String)

@Composable
fun HelpScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenSystemIntent: (Intent) -> Unit,
) {
    val topics = remember {
        listOf(
            HelpTopic(
                "I tap Iris and nothing happens",
                "On Xiaomi, Redmi, POCO, Oppo, Vivo and Realme phones there is a second permission " +
                    "beyond \"Display over other apps\", and without it your phone silently blocks " +
                    "CopyEye from opening the scan screen while another app is in front.\n\n" +
                    "Settings → Apps → CopyEye → Other permissions → turn on \"Display pop-up " +
                    "windows while running in background\".\n\n" +
                    "On the same screen, also turn on Autostart, and set Battery saver to " +
                    "\"No restrictions\" — otherwise the floating eye disappears after a while.",
            ),
            HelpTopic(
                "How do I move Iris?",
                "Touch and drag. Iris follows your finger and snaps to the nearest side when you " +
                    "let go. Dragging never starts a scan — only a clean tap does.",
            ),
            HelpTopic(
                "How do I copy text from a video?",
                "Pause the video on the frame you want, then tap Iris. CopyEye reads the frame " +
                    "that is on screen at that moment. For video that will not pause, turn on " +
                    "Smart Frame Mode in Scan settings — it takes a few frames and keeps the " +
                    "sharpest.",
            ),
            HelpTopic(
                "Why can some screens not be scanned?",
                "Banking apps, DRM video and some password managers mark their windows as " +
                    "protected. Android hands CopyEye a blank image instead of their content. " +
                    "That is deliberate, and CopyEye does not try to get around it.",
            ),
            HelpTopic(
                "Why does Android ask for screen permission every time?",
                "From Android 14 the system requires fresh consent for each capture session. " +
                    "CopyEye keeps one session alive for as long as it is running so you are only " +
                    "asked once per start — but if you stop it, or Android stops it, the next " +
                    "start asks again.",
            ),
            HelpTopic(
                "Iris disappears after a while",
                "Some phones — Xiaomi, Oppo, Vivo, Samsung and others — stop background services " +
                    "aggressively. Exempting CopyEye from battery optimisation usually fixes it. " +
                    "Use the button below.",
            ),
            HelpTopic(
                "Hindi text is not being recognised",
                "Check that Devanagari is turned on under Scan settings → Languages. Mixed " +
                    "Hindi-English screens work best with both scripts enabled.",
            ),
            HelpTopic(
                "The highlights do not line up with the text",
                "This usually means the screen rotated between the capture and the scan. Close " +
                    "the scan and tap Iris again.",
            ),
        )
    }

    DetailScaffold(title = "Help", onBack = onBack) {
        SettingsSection(title = "Common questions") {
            topics.forEachIndexed { index, topic ->
                ExpandableTopic(topic)
                if (index != topics.lastIndex) SettingsDivider()
            }
        }

        SettingsSection(title = "Keeping CopyEye running") {
            SettingsNavigationRow(
                title = "Battery optimisation",
                subtitle = "Open Android's battery settings to stop CopyEye being killed",
                onClick = {
                    onOpenSystemIntent(container.permissionChecker.batteryOptimisationSettingsIntent())
                },
            )
            SettingsDivider()
            SettingsNavigationRow(
                title = "App settings",
                subtitle = "Permissions, notifications, storage",
                onClick = { onOpenSystemIntent(container.permissionChecker.appSettingsIntent()) },
            )
        }

        SettingsSection(title = "Support") {
            SettingsNavigationRow(
                title = "Contact",
                subtitle = "support@example.com — replace before release",
                onClick = {},
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ExpandableTopic(topic: HelpTopic) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(topic.question, style = MaterialTheme.typography.bodyLarge)
        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = topic.answer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
