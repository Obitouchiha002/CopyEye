package com.copyeye.app.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log

/**
 * The one place that touches the system clipboard.
 *
 * Also the one place that knows about clipboard privacy: from Android 13 the system shows a
 * preview of whatever was copied, and CopyEye has no way to know whether the text it just scanned
 * off someone's screen was a password or a poem. Marking the clip sensitive suppresses that
 * preview at a cost of nothing.
 */
class ClipboardWriter(context: Context) {

    private val manager =
        context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    val isAvailable: Boolean get() = manager != null

    /**
     * @param sensitive suppresses the system's copy preview. Defaults to true because OCR of an
     *   arbitrary screen is exactly the case where the content is unknown.
     * @return true when the write succeeded.
     */
    fun copy(text: String, label: String = CLIP_LABEL, sensitive: Boolean = true): Boolean {
        val clipboard = manager ?: return false
        if (text.isEmpty()) return false
        return try {
            val clip = ClipData.newPlainText(label, text)
            if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: SecurityException) {
            // Some OEM builds refuse clipboard writes from a background context.
            Log.w(TAG, "Clipboard write refused")
            false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Clipboard unavailable")
            false
        } catch (e: RuntimeException) {
            // A very large clip can exceed the binder transaction limit.
            Log.w(TAG, "Clipboard write failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private companion object {
        const val TAG = "CopyEye/Clipboard"
        const val CLIP_LABEL = "CopyEye"
    }
}
