package com.copyeye.app.feature.onboarding

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.copyeye.app.core.permissions.PermissionChecker
import com.copyeye.app.ui.components.PermissionChecklist
import com.copyeye.app.ui.components.PermissionStep

/**
 * The permission checklist, shown during onboarding and again on Home whenever something is
 * missing.
 *
 * ## Why it re-reads on resume
 *
 * Two of these steps hand the user to another app and return no result: Android's overlay settings
 * page, and — on Xiaomi, Oppo, Vivo and Realme — an OEM security app three levels deep. The only
 * moment CopyEye can find out what happened is when it comes back to the foreground, so that is
 * when the list refreshes. Without it, a user who granted everything correctly would return to a
 * screen still telling them they had not.
 */
@Composable
fun PermissionsPage(
    permissions: PermissionChecker,
    onRequestOverlay: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenSystemIntent: (Intent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var refreshToken by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshToken++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val canDrawOverlays = remember(refreshToken) { permissions.canDrawOverlays() }
    val canNotify = remember(refreshToken) { permissions.canPostNotifications() }

    // The OEM pop-up permission has no API to read, so it is tracked as "the user says they did
    // it". Being wrong here is recoverable: if it was not actually granted, the first scan reports
    // it with the same instructions.
    var oemAcknowledged by remember { mutableStateOf(false) }

    val steps = buildList {
        add(
            PermissionStep(
                title = "Show the floating eye",
                why = "Lets Iris sit over other apps",
                granted = canDrawOverlays,
                actionLabel = "Allow",
                onAction = onRequestOverlay,
            ),
        )
        if (permissions.needsOemPopupPermission) {
            add(
                PermissionStep(
                    title = "Let CopyEye open over other apps",
                    why = "Your phone needs this as well, or tapping Iris does nothing at all",
                    granted = oemAcknowledged.takeIf { it },
                    actionLabel = "Open",
                    path = permissions.oemPopupPermissionPath,
                    onAction = {
                        oemAcknowledged = true
                        onOpenSystemIntent(permissions.oemPopupPermissionIntent())
                    },
                ),
            )
        }
        add(
            PermissionStep(
                title = "Notifications",
                why = "How you stop CopyEye at any moment",
                granted = canNotify,
                actionLabel = "Allow",
                required = false,
                onAction = onRequestNotifications,
            ),
        )
        permissions.autostartIntent()?.let { intent ->
            add(
                PermissionStep(
                    title = "Autostart",
                    why = "Stops your phone killing CopyEye in the background",
                    granted = null,
                    actionLabel = "Open",
                    required = false,
                    onAction = { onOpenSystemIntent(intent) },
                ),
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Two quick permissions",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "CopyEye cannot see your screen until you tap Iris. These just let it appear " +
                "and open.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        PermissionChecklist(steps = steps)
    }
}
