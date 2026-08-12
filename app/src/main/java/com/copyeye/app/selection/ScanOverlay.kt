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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
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
    /**
     * How much of the bottom of the viewport the toolbar occupies.
     *
     * The frame is fitted *above* it rather than behind it. Letting the toolbar overlap meant the
     * last line or two of every screen — a video's subtitle, the very thing this app is for — sat
     * underneath it, visible but impossible to tap.
     */
    bottomInsetPx: Float = 0f,
) {
    val density = LocalDensity.current
    val touchTolerancePx = with(density) { TOUCH_TOLERANCE_DP.dp.toPx() }

    var viewport by remember { mutableStateOf(Size.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var regionRect by remember { mutableStateOf<TextRect?>(null) }

    val transform = remember(frame, viewport, zoom, pan, bottomInsetPx) {
        FrameTransform.fit(
            bitmapWidth = frame.width,
            bitmapHeight = frame.height,
            viewportWidth = viewport.width,
            viewportHeight = (viewport.height - bottomInsetPx).coerceAtLeast(1f),
        ).copy(zoom = zoom, panX = pan.x, panY = pan.y)
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

        val visibleLines = result?.lines
            ?.map { transform.bitmapToScreen(it.box.expanded(TEXT_PADDING_PX)) }
            ?.filter { it.right > 0 && it.left < size.width && it.bottom > 0 && it.top < size.height }
            .orEmpty()
        val selected = highlights.map { transform.bitmapToScreen(it.expanded(TEXT_PADDING_PX)) }

        // ---- Highlighting ------------------------------------------------------------------
        //
        // This started out clever and looked terrible, so it is worth recording why.
        //
        // The first version dimmed the whole frame and *subtracted* every recognised line from
        // the dim, on the theory that painting nothing over a glyph keeps it readable. On a real
        // screenshot that reads as a page full of white boxes — the unselected text all becomes
        // panels, and the picture underneath disappears behind a grid. Elegant in description,
        // ugly in a photograph of an Instagram post.
        //
        // What it does now is what every other app does when you select text: leave the picture
        // completely alone, and put a translucent wash of the accent behind the words you chose.
        // People already know what that means, which is the entire argument for it.

        // Nothing is drawn for text that is merely *available*. A marker on every recognised line
        // is the box soup again; the hint pill says "tap text to copy", and one tap teaches the
        // rest. The optional underline stays for anyone who wants the old cue.
        if (highlightStyle == HighlightStyle.Underline) {
            visibleLines.forEach { rect ->
                drawLine(
                    color = ScanCyan.copy(alpha = 0.40f),
                    start = Offset(rect.left + TEXT_CORNER_PX, rect.bottom + 1f),
                    end = Offset(rect.right - TEXT_CORNER_PX, rect.bottom + 1f),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
            }
        }

        // ---- Selection ---------------------------------------------------------------------
        //
        // A translucent accent wash, the way a text field or a browser draws it. Strong enough to
        // be unmistakable at a glance, light enough that the words stay legible through it — the
        // alpha is the whole design here, and 0.34 is where dark text on a light photo and light
        // text on a dark one both survive.
        selected.forEach { rect ->
            val pad = SELECTION_PAD_PX
            val padded = rect.expanded(pad)
            drawRoundRectFill(padded, SelectionWash.copy(alpha = 0.34f))
            // A hairline edge, so two adjacent lines still read as two lines rather than a blob.
            drawRoundRect(
                color = SelectionWash.copy(alpha = 0.85f),
                topLeft = Offset(padded.left, padded.top),
                size = Size(padded.width.coerceAtLeast(1f), padded.height.coerceAtLeast(1f)),
                cornerRadius = CornerRadius(TEXT_CORNER_PX),
                style = Stroke(width = 1.5f),
            )
        }

        // ---- Region marquee ----------------------------------------------------------------
        regionRect?.let { rect ->
            drawRoundRectFill(rect, IrisViolet.copy(alpha = 0.12f))
            drawRoundRect(
                color = IrisViolet,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width.coerceAtLeast(1f), rect.height.coerceAtLeast(1f)),
                cornerRadius = CornerRadius(TEXT_CORNER_PX),
                style = Stroke(
                    width = SELECTION_STROKE_PX,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                ),
            )
        }
    }
}

/**
 * The selection colour.
 *
 * Violet rather than the scan cyan: cyan against the blue-white of a phone screenshot is nearly
 * invisible, which is how the old highlight managed to be both loud and unclear at once.
 */
private val SelectionWash = IrisViolet

private fun TextRect.toRoundRect(radius: Float) = RoundRect(
    left = left,
    top = top,
    right = right,
    bottom = bottom,
    cornerRadius = CornerRadius(radius),
)

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

/**
 * The "working on it" state.
 *
 * Shows how long it has been running once a scan passes a second. Recognition cost tracks how
 * textured an image is, so a photo or a video frame can legitimately take several times longer than
 * an app's own text — and a progress animation with no numbers on it is indistinguishable from a
 * hang. A visible counter and a way out are what stop a slow scan from reading as a broken app.
 */
@Composable
fun ScanningBanner(
    reducedMotion: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var elapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val started = System.currentTimeMillis()
        while (true) {
            kotlinx.coroutines.delay(200)
            elapsedMs = System.currentTimeMillis() - started
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        ScanWave(reducedMotion = reducedMotion, modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .fillMaxWidth()
                .padding(bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (elapsedMs < 1_000) {
                    "Reading the screen…"
                } else {
                    "Reading the screen…  ${elapsedMs / 1000}s"
                },
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            if (elapsedMs > SLOW_SCAN_HINT_MS) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Photos and video take longer than app text",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = Color.White)
                }
            }
        }
    }
}

private const val SLOW_SCAN_HINT_MS = 2_500L

private const val TOUCH_TOLERANCE_DP = 12

/** Breathing room around a recognised line, so the cut-out never clips a descender. */
private const val TEXT_PADDING_PX = 4f
private const val TEXT_CORNER_PX = 7f
private const val CORNER_RADIUS_PX = 7f
private const val SELECTION_STROKE_PX = 2.2f
private const val SELECTION_PAD_PX = 3f
private const val SELECTION_GLOW_PX = 7f
private const val MIN_REGION_PX = 24f


/**
 * What is on screen before anything has been selected.
 *
 * Deliberately tiny. The frozen frame is the user's own screen at 1:1, and the whole point of that
 * is that it does not feel like a photograph of their screen — so until there is a selection to act
 * on, the interface gets out of the way and says only what to do next.
 */
@Composable
fun SelectionHint(
    modeLabel: String,
    onCycleMode: () -> Unit,
    onCopyAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 10.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Tap text to copy",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onCycleMode, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(modeLabel, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = onCopyAll, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text("All", style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close")
            }
        }
    }
}
