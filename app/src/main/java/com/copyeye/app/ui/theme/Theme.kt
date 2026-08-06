package com.copyeye.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.copyeye.app.core.common.ApiLevel

/** The blue-violet the whole product hangs off. */
val IrisViolet = Color(0xFF7C6CF0)
val IrisVioletDark = Color(0xFF5B49D6)
val ScanCyan = Color(0xFF4CC7E8)
val ScanRose = Color(0xFFF08BB4)
val SuccessGreen = Color(0xFF3FBF87)
val WarnAmber = Color(0xFFE9A93C)
val DangerRed = Color(0xFFE05B5B)

private val LightScheme = lightColorScheme(
    primary = IrisVioletDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E3FF),
    onPrimaryContainer = Color(0xFF1D1147),
    secondary = Color(0xFF2E7F9B),
    background = Color(0xFFFBFBFE),
    onBackground = Color(0xFF15171E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15171E),
    surfaceVariant = Color(0xFFEFF0F6),
    onSurfaceVariant = Color(0xFF4A4E5C),
    outline = Color(0xFFC9CCD8),
    error = DangerRed,
)

private val DarkScheme = darkColorScheme(
    primary = IrisViolet,
    onPrimary = Color(0xFF17123A),
    primaryContainer = Color(0xFF352C7A),
    onPrimaryContainer = Color(0xFFE7E3FF),
    secondary = ScanCyan,
    background = Color(0xFF0F1116),
    onBackground = Color(0xFFE9EAF0),
    surface = Color(0xFF171A22),
    onSurface = Color(0xFFE9EAF0),
    surfaceVariant = Color(0xFF232733),
    onSurfaceVariant = Color(0xFFB3B8C6),
    outline = Color(0xFF3A3F4E),
    error = Color(0xFFFF8A8A),
)

/** The gradient used for the scan wave and for success moments — and nowhere else. */
val ScanGradient = listOf(IrisViolet, ScanCyan, ScanRose)

fun scanBrush(): Brush = Brush.horizontalGradient(ScanGradient)

private val CopyEyeTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun CopyEyeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Material You is on by default where it exists. The brand accent still shows up in the one
     * place it carries meaning — the scan animation — so following the user's wallpaper elsewhere
     * costs nothing and makes the app feel native.
     */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && ApiLevel.hasDynamicColor ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = CopyEyeTypography,
        content = content,
    )
}
