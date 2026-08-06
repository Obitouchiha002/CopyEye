package com.copyeye.app.selection

import com.copyeye.app.ocr.TextRect

/**
 * Maps between the captured bitmap's pixels and what is on the display.
 *
 * Two transforms are stacked. The first fits the frame into the viewport — the capture and the
 * screen it is drawn on are the same shape, but not once the status bar has been cropped off. The
 * second is the user's own zoom and pan.
 *
 * Every touch coordinate has to make the trip back through both, which is why this is one small
 * immutable value with an explicit inverse rather than arithmetic sprinkled through the gesture
 * handlers.
 */
data class FrameTransform(
    val fitScale: Float,
    val fitOffsetX: Float,
    val fitOffsetY: Float,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    private val totalScale: Float get() = fitScale * zoom

    fun bitmapToScreenX(x: Float): Float = x * totalScale + fitOffsetX * zoom + panX
    fun bitmapToScreenY(y: Float): Float = y * totalScale + fitOffsetY * zoom + panY

    fun screenToBitmapX(x: Float): Float = (x - panX - fitOffsetX * zoom) / totalScale
    fun screenToBitmapY(y: Float): Float = (y - panY - fitOffsetY * zoom) / totalScale

    fun bitmapToScreen(rect: TextRect): TextRect = TextRect(
        bitmapToScreenX(rect.left),
        bitmapToScreenY(rect.top),
        bitmapToScreenX(rect.right),
        bitmapToScreenY(rect.bottom),
    )

    fun screenToBitmap(rect: TextRect): TextRect = TextRect(
        screenToBitmapX(rect.left),
        screenToBitmapY(rect.top),
        screenToBitmapX(rect.right),
        screenToBitmapY(rect.bottom),
    )

    /** A touch tolerance expressed in screen pixels, converted to bitmap pixels. */
    fun toleranceInBitmapPixels(screenPixels: Float): Float =
        if (totalScale <= 0f) screenPixels else screenPixels / totalScale

    companion object {
        /** Fits [bitmapWidth] x [bitmapHeight] inside the viewport without cropping. */
        fun fit(
            bitmapWidth: Int,
            bitmapHeight: Int,
            viewportWidth: Float,
            viewportHeight: Float,
        ): FrameTransform {
            if (bitmapWidth <= 0 || bitmapHeight <= 0) return FrameTransform(1f, 0f, 0f)
            val scale = minOf(
                viewportWidth / bitmapWidth.toFloat(),
                viewportHeight / bitmapHeight.toFloat(),
            )
            return FrameTransform(
                fitScale = scale,
                fitOffsetX = (viewportWidth - bitmapWidth * scale) / 2f,
                fitOffsetY = (viewportHeight - bitmapHeight * scale) / 2f,
            )
        }

        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f
    }
}
