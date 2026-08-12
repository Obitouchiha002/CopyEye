package com.copyeye.app.feature.share

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.copyeye.app.R
import com.copyeye.app.capture.FrameStore
import com.copyeye.app.capture.ScreenFrame
import com.copyeye.app.selection.ScanActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads text out of an image shared from any other app.
 *
 * This is the one route into CopyEye that needs no permission at all — no overlay, no capture
 * consent, no notification, no recording indicator. Android hands us the picture because the user
 * chose to hand it over. For screenshots and saved photos, which is a large slice of why people
 * want this app, it removes every piece of friction the MediaProjection route cannot.
 *
 * It reuses the existing selection screen by putting the decoded image where a captured frame
 * would have gone: same highlighting, same word/line/paragraph selection, same smart actions.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = incomingImage(intent)
        if (uri == null) {
            toastAndClose(R.string.share_no_image)
            return
        }

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(uri) }
            if (bitmap == null) {
                toastAndClose(R.string.share_unreadable)
                return@launch
            }
            // A shared picture has no relationship to the screen it is being viewed on, so the
            // frame describes itself: no crop, no scaling, no rotation left to undo.
            FrameStore.put(
                ScreenFrame(
                    bitmap = bitmap,
                    screenWidth = bitmap.width,
                    screenHeight = bitmap.height,
                ),
            )
            startActivity(
                ScanActivity.shareIntent(this@ShareReceiverActivity)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun incomingImage(intent: Intent): Uri? {
        if (intent.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("image/") != true) return null
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }

    /**
     * Decodes at a sane size and applies the EXIF rotation.
     *
     * A modern phone camera photo is 50+ megapixels; handing that straight to OCR costs seconds
     * and gains nothing, since the recogniser downsamples anyway. And a photo taken in portrait is
     * usually stored landscape with a rotation flag — skip that and every such share reads as
     * gibberish, which looks like the app being bad at Hindi rather than the image being sideways.
     */
    private fun decode(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE_PX) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        val degrees = contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        if (degrees == 0f) return decoded
        Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height,
            Matrix().apply { postRotate(degrees) }, true,
        ).also { if (it !== decoded) decoded.recycle() }
    }.getOrNull()

    private fun toastAndClose(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        /** Beyond this the recogniser gains nothing and the decode starts costing real time. */
        const val MAX_EDGE_PX = 2_600
    }
}
