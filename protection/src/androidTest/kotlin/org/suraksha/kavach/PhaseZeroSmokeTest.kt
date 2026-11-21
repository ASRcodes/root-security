package org.suraksha.kavach

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhaseZeroSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun demoSampleIsEditableAndDoesNotManufactureADangerVerdict() {
        compose.onNodeWithText(compose.activity.getString(R.string.sk_settings)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.sk_demo_lab)).performScrollTo().performClick()
        compose.onNodeWithTag("demo-airtel").performScrollTo().performClick()
        compose.onNodeWithTag("messageInput").assertTextContains("i.airtel.in/rc349sil3")
        compose.onNodeWithTag("manualOutcome").assertDoesNotExist()
        compose.onNodeWithTag("messageInput").performScrollTo().performTextClearance()
        // A URL-free ordinary message does not need any configured reputation service.
        compose.onNodeWithTag("messageInput").performTextInput("Can we meet at 6 pm? Please call me when you are free.")
        compose.onNodeWithTag("scanButton").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("manualOutcome").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.sk_alert_title)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.sk_demo_badge)).assertDoesNotExist()
        assertFalse((compose.activity.application as KavachApplication).demoEnabled.value)
    }
}
