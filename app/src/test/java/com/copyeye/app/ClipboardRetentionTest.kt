package com.copyeye.app

import com.copyeye.app.clipboard.ClipItem
import com.copyeye.app.clipboard.ClipboardHistoryRepository
import com.copyeye.app.data.preferences.HistoryRetention
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClipboardRetentionTest {

    private val now = 1_700_000_000_000L

    private fun item(ageMillis: Long, pinned: Boolean = false) = ClipItem(
        id = now - ageMillis,
        text = "text",
        copiedAtMillis = now - ageMillis,
        pinned = pinned,
    )

    @Test
    fun `items past the window are dropped`() {
        val items = listOf(
            item(ageMillis = 30 * 60_000L), // 30 minutes
            item(ageMillis = 2 * 60 * 60_000L), // 2 hours
        )

        val kept = ClipboardHistoryRepository.prune(items, HistoryRetention.OneHour, now)

        assertThat(kept).hasSize(1)
    }

    @Test
    fun `pinning survives any retention window`() {
        val items = listOf(item(ageMillis = 365L * 24 * 60 * 60_000L, pinned = true))

        val kept = ClipboardHistoryRepository.prune(items, HistoryRetention.OneHour, now)

        assertThat(kept).hasSize(1)
    }

    @Test
    fun `the Never setting keeps everything`() {
        val items = listOf(item(ageMillis = 365L * 24 * 60 * 60_000L))

        val kept = ClipboardHistoryRepository.prune(items, HistoryRetention.Forever, now)

        assertThat(kept).hasSize(1)
    }

    @Test
    fun `an item exactly on the boundary is kept`() {
        val items = listOf(item(ageMillis = HistoryRetention.OneDay.millis!!))

        val kept = ClipboardHistoryRepository.prune(items, HistoryRetention.OneDay, now)

        assertThat(kept).hasSize(1)
    }

    @Test
    fun `the preview is the first non-blank line`() {
        val item = ClipItem(id = 1, text = "\n\nHello world\nsecond", copiedAtMillis = now)

        assertThat(item.preview).isEqualTo("Hello world")
    }

    @Test
    fun `the preview of empty text is empty rather than null`() {
        val item = ClipItem(id = 1, text = "   \n  ", copiedAtMillis = now)

        assertThat(item.preview).isEmpty()
    }
}
