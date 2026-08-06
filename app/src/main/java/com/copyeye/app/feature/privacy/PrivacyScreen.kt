package com.copyeye.app.feature.privacy

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copyeye.app.AppContainer
import com.copyeye.app.capture.FrameStore
import com.copyeye.app.core.state.CopyEyeBus
import com.copyeye.app.ui.components.DetailScaffold
import com.copyeye.app.ui.components.SettingsDivider
import com.copyeye.app.ui.components.SettingsNavigationRow
import com.copyeye.app.ui.components.SettingsSection
import kotlinx.coroutines.launch

/**
 * What CopyEye can see, said plainly, next to the switches that change it.
 *
 * The claims here are ones the code actually keeps, and each one is checkable: no network
 * permission is declared at all, so "nothing is uploaded" is enforced by the manifest rather than
 * by good intentions.
 */
@Composable
fun PrivacyScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onStopService: () -> Unit,
    onOpenSystemIntent: (Intent) -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val projectionActive by CopyEyeBus.projectionActive.collectAsStateWithLifecycle()

    DetailScaffold(title = "Privacy", onBack = onBack) {

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("How CopyEye works", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "CopyEye scans only when you tap the eye.\n\n" +
                    "Screen images are processed on your device by an offline text recogniser " +
                    "that ships inside the app.\n\n" +
                    "Captured frames are not uploaded, not saved to your gallery, and not kept " +
                    "after a scan closes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SettingsSection(title = "What the app cannot do") {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Fact("No internet permission is declared, so nothing can be sent anywhere.")
                Fact("No camera permission. No microphone permission. No location permission.")
                Fact("Screen content is never written to storage or to logs.")
                Fact("Between scans the capture pipeline has no surface attached, so no frames " +
                    "are produced at all.")
                Fact("Recognised text is never used for analytics — there is no analytics.")
            }
        }

        SettingsSection(title = "Right now") {
            SettingsNavigationRow(
                title = if (projectionActive) "Screen reading is active" else "Screen reading is off",
                subtitle = if (projectionActive) {
                    "Stop it to remove CopyEye's access immediately."
                } else {
                    "CopyEye has no access to your screen."
                },
                onClick = { if (projectionActive) onStopService() },
            )
            SettingsDivider()
            SettingsNavigationRow(
                title = "Clear temporary data",
                subtitle = "Drops any frame still held in memory",
                onClick = { FrameStore.clear() },
            )
            SettingsDivider()
            SettingsNavigationRow(
                title = "Clear clipboard history",
                subtitle = "Deletes everything CopyEye has stored on this phone",
                onClick = { scope.launch { container.historyRepository.clearAll() } },
            )
        }

        SettingsSection(title = "Permissions") {
            SettingsNavigationRow(
                title = "App permissions",
                subtitle = "Manage in Android settings",
                onClick = { onOpenSystemIntent(container.permissionChecker.appSettingsIntent()) },
            )
            SettingsDivider()
            SettingsNavigationRow(
                title = "Notification permission",
                subtitle = "CopyEye shows an ongoing notification while it can read your screen. " +
                    "Android requires it, and it is how you stop CopyEye at any time.",
                onClick = onRequestNotificationPermission,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Fact(text: String) {
    Text(
        text = "•  $text",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}
