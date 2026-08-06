package com.copyeye.app.selection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.copyeye.app.data.preferences.HighlightStyle
import com.copyeye.app.ocr.OcrResult
import com.copyeye.app.ocr.TextRect
import com.copyeye.app.ui.theme.IrisViolet
import com.copyeye.app.ui.theme.ScanCyan
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The frozen capture with recognised text drawn on top of it.
 *
 * The frame stays exactly where the real screen was, so the user's eye does not have to re-find the
 * text it was already looking at. Everything else — the dim, the outlines, the toolbar — is layered
 * over that one fixed reference.
 */
@Composable
fun FrozenFrameLayer(
    frame: ImageBitmap,
    result: OcrResult?,
    highlights: List<TextRect>,
    highlightStyle: HighlightStyle,
    dimAmount: Float,
    regionMode: Boolean,
    onTap: (x: Float, y: Float, tolerance: Float) -> Unit,
    onDragStart: (x: Float, y: Float, tolerance: Float) -> Unit,
    onDragTo: (x: Float, y: Float, tolerance: Float) -> Unit,
    onRegion: (TextRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val touchTolerancePx = with(density) { TOUCH_TOLERANCE_DP.dp.toPx() }

    var viewport by remember { mutableStateOf(Size.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var regionRect by remember { mutableStateOf<TextRect?>(null) }

    val transform = remember(frame, viewport, zoom, pan) {
        FrameTransform.fit(frame.width, frame.height, viewport.width, viewport.height)
            .copy(zoom = zoom, panX = pan.x, panY = pan.y)
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(frame, regionMode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var isDrag = false
                    var isMultiTouch = false
                    var startedRegion = false
                    val slop = viewConfiguration.touchSlop
                    var initialDistance = 0f
                    var initialZoom = zoom

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            // Two fingers always mean zoom and pan, never selection.
                            isMultiTouch = true
                            regionRect = null
                            val a = pressed[0].position
                            val b = pressed[1].position
                            val distance = (a - b).getDistance()
                            if (initialDistance == 0f) {
                                initialDistance = distance
                                initialZoom = zoom
                            } else if (initialDistance > 0f) {
                                zoom = (initialZoom * (distance / initialDistance))
                                    .coerceIn(FrameTransform.MIN_ZOOM, FrameTransform.MAX_ZOOM)
                            }
                            val panDelta = pressed
                                .map { it.position - it.previousPosition }
                                .reduce { acc, offset -> acc + offset } / pressed.size.toFloat()
                            pan += panDelta
                            pressed.forEach { it.consume() }
                            continue
                        }

                        if (isMultiTouch) {
                            // Do not turn the tail of a pinch into a selection drag.
                            pressed.forEach { it.consume() }
                            continue
                        }

                        val change: PointerInputChange = pressed.first()
                        val travel = (change.position - down.position).getDistance()

                        if (!isDrag && travel > slop) {
                            isDrag = true
                            if (regionMode) {
                                startedRegion = true
                                regionRect = TextRect(
                                    down.position.x, down.position.y,
                                    change.position.x, change.position.y,
                                )
                            } else {
                                onDragStart(
                                    transform.screenToBitmapX(down.position.x),
                                    transform.screenToBitmapY(down.position.y),
                                    transform.toleranceInBitmapPixels(touchTolerancePx),
                                )
                            }
                        }

                        if (isDrag && change.positionChanged()) {
                            if (startedRegion) {
                                regionRect = TextRect(
                                    minOf(down.position.x, change.position.x),
                                    minOf(down.position.y, change.position.y),
                                    maxOf(down.position.x, change.position.x),
                                    maxOf(down.position.y, change.position.y),
                                )
                            } else {
                                onDragTo(
                                    transform.screenToBitmapX(change.position.x),
                                    transform.screenToBitmapY(change.position.y),
                                    transform.toleranceInBitmapPixels(touchTolerancePx),
                                )
                            }
                            change.consume()
                        }
                    }

                    when {
                        startedRegion -> {
                            regionRect?.let { rect ->
                                if (rect.width > MIN_REGION_PX && rect.height > MIN_REGION_PX) {
                                    onRegion(transform.screenToBitmap(rect))
                                }
                            }
                            regionRect = null
                        }
                        !isDrag && !isMultiTouch -> onTap(
                            transform.screenToBitmapX(down.position.x),
                            transform.screenToBitmapY(down.position.y),
                            transform.toleranceInBitmapPixels(touchTolerancePx),
                        )
                    }
                }
            },
    ) {
        val dstOffset = IntOffset(
            transform.bitmapToScreenX(0f).roundToInt(),
            transform.bitmapToScreenY(0f).roundToInt(),
        )
        val dstSize = IntSize(
            (frame.width * transform.fitScale * zoom).roundToInt().coerceAtLeast(1),
            (frame.height * transform.fitScale * zoom).roundToInt().coerceAtLeast(1),
        )
        drawImage(image = frame, dstOffset = dstOffset, dstSize = dstSize)

        // Dim everything, then leave the recognised text at full brightness so it stays readable.
        drawRect(color = Color.Black.copy(alpha = dimAmount.coerceIn(0f, 0.85f)))

        result?.lines?.forEach { line ->
            val rect = transform.bitmapToScreen(line.box.expanded(OUTLINE_PADDING_PX))
            if (rect.right < 0 || rect.left > size.width) return@forEach
            when (highlightStyle) {
                HighlightStyle.Outline -> drawRoundRectStroke(rect, IrisViolet.copy(alpha = 0.55f))
                HighlightStyle.Fill -> drawRoundRectFill(rect, IrisViolet.copy(alpha = 0.14f))
                HighlightStyle.Underline -> drawLine(
                    color = ScanCyan.copy(alpha = 0.7f),
                    start = Offset(rect.left, rect.bottom),
                    end = Offset(rect.right, rect.bottom),
                    strokeWidth = 2f,
                )
            }
        }

        highlights.forEach { box ->
            val rect = transform.bitmapToScreen(box.expanded(OUTLINE_PADDING_PX))
            drawRoundRectFill(rect, ScanCyan.copy(alpha = 0.34f))
            drawRoundRectStroke(rect, ScanCyan)
        }

        regionRect?.let { rect ->
            drawRoundRectFill(rect, IrisViolet.copy(alpha = 0.16f))
            drawRoundRectStroke(rect, IrisViolet)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundRectStroke(
    rect: TextRect,
    color: Color,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width.coerceAtLeast(1f), rect.height.coerceAtLeast(1f)),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(CORNER_RADIUS_PX),
        style = Stroke(width = 1.6f),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundRectFill(
    rect: TextRect,
    color: Color,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width.coerceAtLeast(1f), rect.height.coerceAtLeast(1f)),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(CORNER_RADIUS_PX),
    )
}

/** A short message centred on a dimmed screen, used for every terminal state. */
@Composable
fun ScanMessage(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.padding(32.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** The "Copied" confirmation. Small, brief, and never a dialog. */
@Composable
fun CopiedToast(visible: Boolean, characterCount: Int, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(imageVector = Icons.Rounded.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (characterCount > 0) "Copied · $characterCount characters" else "Copied",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
fun ScanningBanner(reducedMotion: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        ScanWave(reducedMotion = reducedMotion, modifier = Modifier.fillMaxSize())
        Text(
            text = "Reading the screen…",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .fillMaxWidth()
                .padding(bottom = 96.dp),
            textAlign = TextAlign.Center,
        )
    }
}

private const val TOUCH_TOLERANCE_DP = 12
private const val OUTLINE_PADDING_PX = 2f
private const val CORNER_RADIUS_PX = 6f
private const val MIN_REGION_PX = 24f
