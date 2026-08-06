package com.copyeye.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.copyeye.app.data.preferences.AnimationIntensity
import com.copyeye.app.data.preferences.EyeAccent
import com.copyeye.app.data.preferences.HistoryRetention
import com.copyeye.app.data.preferences.OcrScript
import com.copyeye.app.data.preferences.SettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DataStore round-trips, including the clamping that stops a bad stored value from producing an
 * invisible eye or a zero-second dim timer.
 */
@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun settingsSurviveAWriteAndRead() = runBlocking {
        val repository = SettingsRepository(context)

        repository.update {
            it.copy(
                eyeSizeDp = 50,
                eyeAccent = EyeAccent.Cyan,
                animationIntensity = AnimationIntensity.Full,
                historyEnabled = true,
                historyRetention = HistoryRetention.SevenDays,
                scripts = setOf(OcrScript.Devanagari),
            )
        }

        val stored = repository.settings.first()

        assertThat(stored.eyeSizeDp).isEqualTo(50)
        assertThat(stored.eyeAccent).isEqualTo(EyeAccent.Cyan)
        assertThat(stored.animationIntensity).isEqualTo(AnimationIntensity.Full)
        assertThat(stored.historyEnabled).isTrue()
        assertThat(stored.historyRetention).isEqualTo(HistoryRetention.SevenDays)
        assertThat(stored.scripts).containsExactly(OcrScript.Devanagari)
    }

    @Test
    fun outOfRangeValuesAreClampedRatherThanStored() = runBlocking {
        val repository = SettingsRepository(context)

        repository.update { it.copy(eyeSizeDp = 5_000, idleOpacity = 12f, autoDimDelayMs = 0L) }
        val stored = repository.settings.first()

        assertThat(stored.eyeSizeDp).isAtMost(SettingsRepository.EYE_SIZE_MAX)
        assertThat(stored.idleOpacity).isAtMost(1f)
        assertThat(stored.autoDimDelayMs).isAtLeast(1_000L)
    }

    @Test
    fun positionIsStoredAsAFractionAndClamped() = runBlocking {
        val repository = SettingsRepository(context)

        repository.savePosition(xFraction = 2.5f, yFraction = -1f)
        val stored = repository.settings.first()

        assertThat(stored.eyePositionXFraction).isEqualTo(1f)
        assertThat(stored.eyePositionYFraction).isEqualTo(0f)
    }
}
