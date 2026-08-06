package com.copyeye.app

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.copyeye.app.clipboard.ClipboardWriter
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The copy actually has to reach the system clipboard — the one behaviour a unit test cannot cover
 * and the one the whole product is named after.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardWriterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Test
    fun copiedTextReachesTheSystemClipboard() {
        val writer = ClipboardWriter(context)
        val text = "मैंने यह कॉपी किया — I copied this"

        assertThat(writer.copy(text)).isTrue()

        val clip = clipboard.primaryClip
        assertThat(clip).isNotNull()
        assertThat(clip!!.getItemAt(0).text.toString()).isEqualTo(text)
    }

    @Test
    fun emptyTextIsRefused() {
        assertThat(ClipboardWriter(context).copy("")).isFalse()
    }

    @Test
    fun multiLineTextKeepsItsLineBreaks() {
        val writer = ClipboardWriter(context)
        val text = "first line\nsecond line\n\nnew paragraph"

        writer.copy(text)

        assertThat(clipboard.primaryClip!!.getItemAt(0).text.toString()).isEqualTo(text)
    }
}
