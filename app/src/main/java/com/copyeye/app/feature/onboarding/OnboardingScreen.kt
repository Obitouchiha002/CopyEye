package com.copyeye.app.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copyeye.app.AppContainer
import com.copyeye.app.core.state.CopyEyeBus
import com.copyeye.app.ui.components.IrisMark
import kotlinx.coroutines.launch

private const val PERMISSIONS_PAGE = 1

private data class OnboardingPage(
    val title: String,
    val body: String,
    val actionLabel: String?,
)

/**
 * Four screens, in the order the user needs them.
 *
 * Permissions are one page, not several. An earlier version walked the user through them one at a
 * time, which read well but hid the thing that actually matters: whether each one worked. Two of
 * these steps hand control to another app and return no result, so a checklist that is still on
 * screen when the user comes back — with a tick or without one — is the only design that tells the
 * truth about where they stand.
 */
@Composable
fun OnboardingScreen(
    container: AppContainer,
    onRequestOverlayPermission: () -> Unit,
    onRequestCapturePermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenSystemIntent: (android.content.Intent) -> Unit,
    onFinished: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val serviceRunning by CopyEyeBus.serviceRunning.collectAsStateWithLifecycle()

    val pages = remember {
        listOf(
            OnboardingPage(
                title = "Copy text from anywhere",
                body = "Videos, Reels, screenshots, games, PDFs, apps that block selection — if " +
                    "you can see the text, CopyEye can copy it.",
                actionLabel = null,
            ),
            // Index 1 is the permission checklist, rendered by PermissionsPage rather than by
            // the generic page body.
            OnboardingPage(title = "", body = "", actionLabel = null),
            OnboardingPage(
                title = "Everything stays on your phone",
                body = "Text recognition runs on this device. Captured screens are never uploaded " +
                    "and never saved to your gallery. They are deleted the moment you close a scan.",
                actionLabel = null,
            ),
            OnboardingPage(
                title = "Try it now",
                body = "मैंने यह टेक्स्ट कॉपी किया — I copied this text.\n\nTap Iris at the edge " +
                    "of your screen, then tap this line to select it.",
                actionLabel = null,
            ),
        )
    }

    val pagerState = rememberPagerState(pageCount = { pages.size })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { index ->
            // if/else rather than an early return: a non-local return out of a composable lambda
            // leaves Compose's group structure inconsistent, which shows up as a blank page.
            if (index == PERMISSIONS_PAGE) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    PermissionsPage(
                        permissions = container.permissionChecker,
                        onRequestOverlay = onRequestOverlayPermission,
                        onRequestNotifications = onRequestNotificationPermission,
                        onOpenSystemIntent = onOpenSystemIntent,
                    )
                }
            } else {
            val page = pages[index]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IrisMark(modifier = Modifier.size(132.dp))
                Spacer(Modifier.height(24.dp))
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = page.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (page.actionLabel != null) {
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onRequestCapturePermission) { Text(page.actionLabel) }
                }
            }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { complete(container, scope, onFinished) }) { Text("Skip") }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(pages.size) { dot ->
                    Surface(
                        shape = CircleShape,
                        color = if (dot == pagerState.currentPage) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        modifier = Modifier.size(if (dot == pagerState.currentPage) 9.dp else 7.dp),
                    ) {}
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    if (pagerState.currentPage == pages.lastIndex) {
                        complete(container, scope, onFinished)
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
            ) {
                Text(if (pagerState.currentPage == pages.lastIndex) "Done" else "Next")
            }
        }

        if (serviceRunning) {
            Text(
                text = "CopyEye is running — Iris is at the edge of your screen.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun complete(
    container: AppContainer,
    scope: kotlinx.coroutines.CoroutineScope,
    onFinished: () -> Unit,
) {
    scope.launch {
        container.settingsRepository.update { it.copy(onboardingComplete = true) }
        onFinished()
    }
}
