package com.copyeye.app.selection

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.copyeye.app.ui.theme.ScanCyan
import com.copyeye.app.ui.theme.ScanRose
import com.copyeye.app.ui.theme.IrisViolet

/**
 * The gradient wave that sweeps the frozen frame while recognition runs.
 *
 * It starts the instant the frame appears rather than when OCR finishes, which is the whole point:
 * the user tapped, something visibly happened, and the perceived latency collapses even though the
 * work underneath takes as long as it takes.
 *
 * When motion is suppressed the wave is replaced with a static tint — the screen still reads as
 * "working", with nothing moving.
 */
@Composable
fun ScanWave(
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    if (reducedMotion) {
        Canvas(modifier) {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(IrisViolet.copy(alpha = 0.16f), ScanCyan.copy(alpha = 0.10f)),
                ),
            )
        }
        return
    }

    val transition = rememberInfiniteTransition(label = "scan-wave")
    val progress by transition.animateFloat(
        initialValue = -0.35f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SWEEP_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    Canvas(modifier) {
        val height = size.height
        val centre = progress * height
        val band = height * BAND_FRACTION

        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.5f to IrisViolet.copy(alpha = 0.34f),
                    1f to Color.Transparent,
                ),
                startY = centre - band,
                endY = centre + band,
            ),
        )
        // A thin bright leading edge is what makes it read as a scan rather than a glow.
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, ScanCyan, ScanRose, Color.Transparent),
            ),
            start = Offset(0f, centre),
            end = Offset(size.width, centre),
            strokeWidth = 2.5f,
        )
    }
}

/**
 * How long the sweep takes to cross the screen once.
 *
 * Tuned so a scan that completes at the usual few hundred milliseconds shows most of one pass —
 * long enough to be seen, short enough that a slow scan loops rather than crawling.
 */
private const val SWEEP_DURATION_MS = 900

private const val BAND_FRACTION = 0.16f
