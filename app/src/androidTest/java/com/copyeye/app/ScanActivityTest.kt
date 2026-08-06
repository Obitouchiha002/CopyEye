package com.copyeye.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.copyeye.app.selection.ScanActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The selection overlay's failure path, which is the one a user is most likely to hit and least
 * likely to forgive: a scan launched with nothing to show must explain itself and offer a way out,
 * never hang on an empty screen.
 *
 * The activity is launched with an empty frame slot, which is exactly what happens when the process
 * is trimmed between the capture and the scan.
 */
@RunWith(AndroidJUnit4::class)
class ScanActivityTest {

    @get:Rule
    val rule = createAndroidComposeRule<ScanActivity>()

    @Test
    fun aScanWithNoCapturedFrameReportsAFailureAndOffersAWayOut() {
        rule.onNodeWithText("Scan failed").assertIsDisplayed()
        rule.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test
    fun closingTheOverlayFinishesTheActivity() {
        rule.onNodeWithText("Close").performClick()
        rule.waitForIdle()

        rule.waitUntil(timeoutMillis = 3_000) { rule.activity.isFinishing }
    }
}
