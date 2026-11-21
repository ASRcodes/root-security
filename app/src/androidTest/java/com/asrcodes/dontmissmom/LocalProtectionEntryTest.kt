package com.asrcodes.dontmissmom

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.firebase.auth.FirebaseAuth
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.suraksha.core.DemoExamples
import org.suraksha.kavach.data.AppSettings
import org.suraksha.kavach.R as ProtectionR

/** An unauthenticated emulator only. Never signs out, clears data or sends a real family alert. */
@RunWith(AndroidJUnit4::class)
class LocalProtectionEntryTest {
    @Test fun signedOutUserCanOpenAndEditLocalProtectionWithoutCreatingAnAccount() {
        assumeTrue("Run only on an emulator", Build.HARDWARE in setOf("ranchu", "goldfish") ||
            Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk_gphone"))
        val app = ApplicationProvider.getApplicationContext<DmmTestApplication>()
        assertNull("Use a signed-out emulator; this test never signs out a user", FirebaseAuth.getInstance().currentUser)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val prefs = FirstLaunchPermissions.preferences(app)
        val keys = listOf(FirstLaunchPermissions.COMPLETE, FirstLaunchPermissions.NOTICE_PENDING)
        val previous = keys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        val oldFactory = AppDependencies.repositoryFactory
        val repository = FakeRepository()
        val oldSmsCount = app.sms.size
        AppDependencies.repositoryFactory = { repository }
        assertTrue(prefs.edit().putBoolean(FirstLaunchPermissions.COMPLETE, true)
            .putBoolean(FirstLaunchPermissions.NOTICE_PENDING, false).commit())
        try {
            ActivityScenario.launch(LoginActivity::class.java).use {
                onView(withId(R.id.btnOpenLocalProtection)).perform(scrollTo(), click())
                val localized = AppSettings.localized(app)
                val ui = RootSecurityUiHarness(app)
                ui.clickText(localized.getString(ProtectionR.string.sk_check_a_message), towardBottom = true)
                var textSet = false
                // Focus/keyboard changes can replace Compose's accessibility node on API 26.
                // Retry only that stale-object race, always with a fresh editor lookup.
                for (attempt in 1..4) {
                    device.waitForIdle(1_000)
                    val editor = ui.findEditableText("", towardBottom = true)
                    try {
                        editor.text = DemoExamples.NORMAL_URL
                        textSet = true
                        break
                    } catch (stale: StaleObjectException) {
                        if (attempt == 4) throw stale
                    }
                }
                assertTrue("Setting the actual message editor must succeed", textSet)
                // ACTION_SET_TEXT schedules Compose state/semantics updates. Re-query instead of
                // reading the pre-edit UiObject2's cached accessibility node immediately.
                val updatedEditor = requireNotNull(device.wait(Until.findObject(
                    By.pkg(app.packageName).clazz("android.widget.EditText")
                        .textContains(DemoExamples.NORMAL_URL)), 10_000)) {
                    "The message editor did not expose the entered URL within 10 seconds"
                }
                assertTrue("The real checker must retain the entered URL",
                    updatedEditor.text.contains(DemoExamples.NORMAL_URL))
                assertNull(FirebaseAuth.getInstance().currentUser)
                assertTrue("Local entry must not send SOS", repository.sent.isEmpty())
                assertEquals("Local entry must not send guardian SMS", oldSmsCount, app.sms.size)
                device.pressBack()
                device.pressBack()
            }
        } finally {
            AppDependencies.repositoryFactory = oldFactory
            val edit = prefs.edit()
            previous.forEach { (key, value) -> if (value == null) edit.remove(key) else edit.putBoolean(key, value) }
            assertTrue(edit.commit())
        }
    }
}
