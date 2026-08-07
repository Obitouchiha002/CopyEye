package com.copyeye.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.copyeye.app.ui.components.IrisMark
import com.copyeye.app.ui.components.SettingsNavigationRow
import com.copyeye.app.ui.theme.IrisViolet
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
    onRequestNotificationPermission: () -> Unit,
    onOpenSystemIntent: (android.content.Intent) -> Unit,
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
            HeroCard(running = serviceRunning, canDrawOverlays = canDrawOverlays)

            Spacer(Modifier.height(8.dp))

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (!serviceRunning) {
                    Button(
                        onClick = {
                            if (canDrawOverlays) onStartService() else onRequestOverlayPermission()
                        },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Text(
                            text = if (canDrawOverlays) "Start CopyEye" else "Allow floating eye",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onStopService,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Text("Stop CopyEye")
                    }
                }
            }

            // The checklist stays on Home until everything is in place. A permission that was
            // never granted is the single most common reason this app appears broken, and it must
            // not be something the user has to go looking for.
            if (!canDrawOverlays || !serviceRunning) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    com.copyeye.app.feature.onboarding.PermissionsPage(
                        permissions = container.permissionChecker,
                        onRequestOverlay = onRequestOverlayPermission,
                        onRequestNotifications = onRequestNotificationPermission,
                        onOpenSystemIntent = onOpenSystemIntent,
                    )
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

/**
 * The top of Home: Iris herself, the one sentence that matters, and nothing else.
 *
 * An earlier version led with a status dot and a paragraph. It was accurate and completely
 * forgettable. The character is the product's whole personality, so she gets the space — and the
 * status becomes a small pill under her rather than the headline.
 */
@Composable
private fun HeroCard(running: Boolean, canDrawOverlays: Boolean) {
    val (headline, body, tint) = when {
        running -> Triple(
            "Iris is watching your edge",
            "Tap her over any app to grab the text. Drag to move her.",
            SuccessGreen,
        )
        !canDrawOverlays -> Triple(
            "One permission away",
            "Android needs to let CopyEye draw over other apps.",
            WarnAmber,
        )
        else -> Triple(
            "Ready when you are",
            "Turn CopyEye on and Iris appears at the edge of your screen.",
            WarnAmber,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                IrisViolet.copy(alpha = 0.14f),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .padding(top = 28.dp, bottom = 24.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IrisMark(
                    animate = running,
                    modifier = Modifier.size(112.dp),
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = headline,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = tint.copy(alpha = 0.16f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = tint,
                            modifier = Modifier.size(8.dp),
                        ) {}
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (running) "On · reads only when you tap" else "Off",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
