package com.copyeye.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import com.copyeye.app.ui.theme.IrisViolet
import com.copyeye.app.ui.theme.IrisVioletDark
import com.copyeye.app.ui.theme.ScanCyan
import kotlin.math.min

/**
 * Iris, at any size, for use inside the app's own screens.
 *
 * The same character as the floating overlay, drawn again in Compose rather than shared with it.
 * `IrisEyeView` is a `View` tuned for sitting on screen for hours at near-zero cost; this one is
 * free to be prettier — a soft aura, a slow blink — because it is only ever on screen while the
 * user is looking at the app.
 */
@Composable
fun IrisMark(
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    accent: Color = IrisViolet,
) {
    val transition = rememberInfiniteTransition(label = "iris")

    // A blink is 90% of a cycle spent open. Anything more frequent reads as a nervous tic.
    val openness by if (animate) {
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 5_200
                    1f at 0
                    1f at 4_700
                    0.08f at 4_850
                    1f at 5_000
                },
                repeatMode = RepeatMode.Restart,
            ),
            label = "blink",
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(1f) }
    }

    val aura by if (animate) {
        transition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(2_600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "aura",
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(1f) }
    }

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = min(size.width, size.height) / 2f * 0.62f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.26f), Color.Transparent),
                center = Offset(cx, cy),
                radius = r * 2.1f * aura,
            ),
            radius = r * 2.1f * aura,
            center = Offset(cx, cy),
        )

        val lid = (r * openness).coerceAtLeast(0.5f)
        clipRect(left = cx - r, top = cy - lid, right = cx + r, bottom = cy + lid) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color(0xFFE9EBF3)),
                    center = Offset(cx, cy - r * 0.2f),
                    radius = r,
                ),
                radius = r,
                center = Offset(cx, cy),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent, IrisVioletDark),
                    center = Offset(cx, cy),
                    radius = r * 0.55f,
                ),
                radius = r * 0.52f,
                center = Offset(cx, cy),
            )
            drawCircle(
                color = Color(0xFF10121A),
                radius = r * 0.23f,
                center = Offset(cx, cy),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.95f),
                radius = r * 0.10f,
                center = Offset(cx - r * 0.18f, cy - r * 0.19f),
            )
            // A cool rim light on the far side, which is what stops it reading as a flat sticker.
            drawCircle(
                color = ScanCyan.copy(alpha = 0.35f),
                radius = r * 0.055f,
                center = Offset(cx + r * 0.26f, cy + r * 0.16f),
            )
        }
    }
}
