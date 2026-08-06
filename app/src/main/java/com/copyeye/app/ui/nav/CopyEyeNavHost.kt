package com.copyeye.app.ui.nav

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.copyeye.app.AppContainer
import com.copyeye.app.feature.help.HelpScreen
import com.copyeye.app.feature.history.HistoryScreen
import com.copyeye.app.feature.home.HomeScreen
import com.copyeye.app.feature.onboarding.OnboardingScreen
import com.copyeye.app.feature.privacy.PrivacyScreen
import com.copyeye.app.feature.settings.AppearanceScreen
import com.copyeye.app.feature.settings.ScanSettingsScreen
import kotlinx.coroutines.flow.first

/** Every destination in the app. */
sealed class Route(val path: String) {
    data object Onboarding : Route("onboarding")
    data object Home : Route("home")
    data object Appearance : Route("appearance")
    data object ScanSettings : Route("scan-settings")
    data object History : Route("history")
    data object Privacy : Route("privacy")
    data object Help : Route("help")
}

@Composable
fun CopyEyeNavHost(
    container: AppContainer,
    startRoute: String?,
    onRequestOverlayPermission: () -> Unit,
    onRequestCapturePermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStopService: () -> Unit,
    onOpenSystemIntent: (Intent) -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    // Onboarding is only ever shown once, so the start destination cannot be decided until the
    // stored flag has been read. Until then there is nothing to draw.
    val onboardingComplete by produceState<Boolean?>(initialValue = null) {
        value = container.settingsRepository.settings.first().onboardingComplete
    }
    val complete = onboardingComplete ?: return

    val start = when {
        !complete -> Route.Onboarding.path
        startRoute != null -> startRoute
        else -> Route.Home.path
    }

    NavHost(navController = navController, startDestination = start) {

        composable(Route.Onboarding.path) {
            OnboardingScreen(
                container = container,
                onRequestOverlayPermission = onRequestOverlayPermission,
                onRequestCapturePermission = onRequestCapturePermission,
                onFinished = {
                    navController.navigate(Route.Home.path) {
                        popUpTo(Route.Onboarding.path) { inclusive = true }
                    }
                },
            )
        }

        composable(Route.Home.path) {
            HomeScreen(
                container = container,
                onRequestOverlayPermission = onRequestOverlayPermission,
                onRequestCapturePermission = onRequestCapturePermission,
                onStopService = onStopService,
                onNavigate = { route -> navController.navigate(route.path) },
            )
        }

        composable(Route.Appearance.path) {
            AppearanceScreen(
                container = container,
                onBack = navController::popBackStack,
                onNavigate = { route -> navController.navigate(route.path) },
            )
        }

        composable(Route.ScanSettings.path) {
            ScanSettingsScreen(container = container, onBack = navController::popBackStack)
        }

        composable(Route.History.path) {
            HistoryScreen(container = container, onBack = navController::popBackStack)
        }

        composable(Route.Privacy.path) {
            PrivacyScreen(
                container = container,
                onBack = navController::popBackStack,
                onStopService = onStopService,
                onOpenSystemIntent = onOpenSystemIntent,
                onRequestNotificationPermission = onRequestNotificationPermission,
            )
        }

        composable(Route.Help.path) {
            HelpScreen(
                container = container,
                onBack = navController::popBackStack,
                onOpenSystemIntent = onOpenSystemIntent,
            )
        }
    }
}
