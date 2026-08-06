package com.copyeye.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copyeye.app.AppContainer
import com.copyeye.app.core.state.CopyEyeBus
import com.copyeye.app.ui.components.SettingsNavigationRow
import com.copyeye.app.ui.components.SettingsSection
import com.copyeye.app.ui.nav.Route
import com.copyeye.app.ui.theme.SuccessGreen
import com.copyeye.app.ui.theme.WarnAmber

/**
 * The switch, the status, and the way in to everything else.
 *
 * What the user needs from this screen is a truthful answer to one question: is it on, and if not,
 * what is missing. Everything else is secondary and lives below the fold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onRequestOverlayPermission: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    val serviceRunning by CopyEyeBus.serviceRunning.collectAsStateWithLifecycle()
    val canDrawOverlays = container.permissionChecker.canDrawOverlays()

    Scaffold(
        topBar = { TopAppBar(title = { Text("CopyEye") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            StatusCard(running = serviceRunning, canDrawOverlays = canDrawOverlays)

            Spacer(Modifier.height(8.dp))

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (!serviceRunning) {
                    Button(
                        onClick = {
                            if (canDrawOverlays) onStartService() else onRequestOverlayPermission()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (canDrawOverlays) "Start CopyEye" else "Allow floating eye")
                    }
                } else {
                    OutlinedButton(onClick = onStopService, modifier = Modifier.fillMaxWidth()) {
                        Text("Stop CopyEye")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            SettingsSection(title = "Privacy") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = CircleShape, color = SuccessGreen, modifier = Modifier.size(10.dp)) {}
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Column {
                        Text("On-device processing", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "Screens are read on this phone. Nothing is uploaded or saved.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SettingsSection(title = "Set up") {
                SettingsNavigationRow(
                    title = "Appearance",
                    subtitle = "Eye style, size, dimming and motion",
                    icon = Icons.Rounded.Palette,
                    onClick = { onNavigate(Route.Appearance) },
                )
                SettingsNavigationRow(
                    title = "Scan settings",
                    subtitle = "Languages, speed, what happens after a copy",
                    icon = Icons.Rounded.Tune,
                    onClick = { onNavigate(Route.ScanSettings) },
                )
                SettingsNavigationRow(
                    title = "Clipboard history",
                    subtitle = "Off by default",
                    icon = Icons.Rounded.History,
                    onClick = { onNavigate(Route.History) },
                )
                SettingsNavigationRow(
                    title = "Privacy",
                    subtitle = "What CopyEye can and cannot see",
                    icon = Icons.Rounded.Lock,
                    onClick = { onNavigate(Route.Privacy) },
                )
                SettingsNavigationRow(
                    title = "Help",
                    subtitle = "Dragging, video text, protected screens, battery",
                    icon = Icons.Rounded.HelpOutline,
                    onClick = { onNavigate(Route.Help) },
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(running: Boolean, canDrawOverlays: Boolean) {
    val (title, body, tint) = when {
        running -> Triple(
            "CopyEye is on",
            "Tap Iris at the edge of your screen to scan. Drag to move it. CopyEye has no access " +
                "to your screen until you tap.",
            SuccessGreen,
        )
        !canDrawOverlays -> Triple(
            "Floating eye is blocked",
            "Android needs permission to draw CopyEye over other apps.",
            WarnAmber,
        )
        else -> Triple(
            "CopyEye is off",
            "Turn it on to start copying text from your screen.",
            WarnAmber,
        )
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(shape = CircleShape, color = tint, modifier = Modifier.size(12.dp)) {}
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
