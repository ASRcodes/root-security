package com.asrcodes.dontmissmom

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.suraksha.core.Level
import org.suraksha.core.ReasonCode
import org.suraksha.kavach.LoadState
import org.suraksha.kavach.ShareFileActivity
import org.suraksha.kavach.notifications.AlertDispatcher
import org.suraksha.kavach.R as ProtectionR
import org.suraksha.kavach.MainActivity as ProtectionActivity
import java.io.File

/** Emulator-only suite: resets protection data/settings. Never run on a user's phone. */
@RunWith(AndroidJUnit4::class)
class ProtectionIntegrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<DmmTestApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private lateinit var repository: FakeRepository

    @Before fun setup() = runBlocking {
        repository = FakeRepository()
        AppDependencies.repositoryFactory = { repository }
        FirstLaunchPermissions.preferences(app).edit()
            .putBoolean(FirstLaunchPermissions.COMPLETE, true)
            .putBoolean(FirstLaunchPermissions.NOTICE_PENDING, false).commit()
        assertTrue("Protection reset must complete without erasing the host", app.clearProtectionData())
        awaitReady()
        app.settings.setLanguage("en")
        app.settings.finishSetup()
        app.settings.setProtection(true)
        app.settings.setEnhanced(false)
        app.setDemo(false)
        app.sms.clear()
    }

    @After fun cleanup() {
        AppDependencies.repositoryFactory = null
        app.dismissWarnings()
        SirenSettings(app).enable()
        File(app.filesDir, "host-session-integration.txt").delete()
    }

    @Suppress("DEPRECATION")
    @Test fun combinedApplicationKeepsOneDontMissMomLauncher() {
        val launchers = app.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName), 0
        )
        assertEquals("com.asrcodes.dontmissmom", app.packageName)
        assertEquals(listOf(LoginActivity::class.java.name), launchers.map { it.activityInfo.name })
        val protection = app.packageManager.getActivityInfo(ComponentName(app, ProtectionActivity::class.java), 0)
        assertFalse("Protection is opened through DontMissMOM, not a second launcher", protection.exported)
        val share = app.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive").setPackage(app.packageName), 0
        )
        assertTrue("File sharing must reach the integrated scanner",
            share.any { it.activityInfo.name == "org.suraksha.kavach.ShareFileActivity" })
    }

    @Test fun protectionTabSurvivesRotationAndEmergencyNavigationRemainsAvailable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.nav_protection)).perform(click())
            onView(withId(R.id.protectionPage)).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withId(R.id.protectionPage)).check(matches(isDisplayed()))
            capture("combined-protection-home")
            scenario.onActivity { app.settings.setProtection(false) }
            assertTrue("Pausing protection must update the host status",
                device.wait(Until.hasObject(By.text(app.getString(R.string.protection_paused))), 5_000))
            scenario.onActivity { app.settings.setProtection(true) }
            pressBack()
            onView(withId(R.id.homePage)).check(matches(isDisplayed()))
            onView(withId(R.id.nav_circle)).perform(click())
            onView(withId(R.id.circlePage)).check(matches(isDisplayed()))
            onView(withId(R.id.nav_history)).perform(click())
            onView(withId(R.id.historyPage)).check(matches(isDisplayed()))
            onView(withId(R.id.nav_settings)).perform(click())
            onView(withId(R.id.settingsPage)).check(matches(isDisplayed()))
            assertTrue("Opening protection must never submit an emergency alert", repository.sent.isEmpty())
        }
    }

    @Test fun everyProtectionEntryOpensItsScreenAndReturnsToTheHostTab() {
        val routes = listOf(
            R.id.btnProtectionCheck to "CHECK", R.id.btnProtectionFiles to "FILES",
            R.id.btnProtectionSetup to "SETUP", R.id.btnProtectionGuardian to "GUARDIAN",
            R.id.btnProtectionCode to "CODE", R.id.btnProtectionCall to "CALL",
            R.id.btnProtectionHistory to "ACTIVITY", R.id.btnProtectionSettings to "SETTINGS"
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.nav_protection)).perform(click())
            for ((button, page) in routes) {
                val monitor = instrumentation.addMonitor(ProtectionActivity::class.java.name, null, false)
                try {
                    onView(withId(button)).perform(scrollTo(), click())
                    val activity = monitor.waitForActivityWithTimeout(10_000)
                    assertNotNull("$page did not open the protection activity", activity)
                    assertEquals(page, activity!!.intent.getStringExtra("page"))
                    if (page == "CHECK") {
                        assertTrue("The manual scanner must render",
                            device.wait(Until.hasObject(By.text(app.getString(ProtectionR.string.sk_check_a_message))), 10_000))
                        capture("combined-message-check")
                    }
                    val back = device.wait(Until.findObject(By.text(app.getString(ProtectionR.string.sk_back_to_dontmissmom))), 10_000)
                    assertNotNull("$page must offer a direct return to DontMissMOM", back)
                    back!!.click()
                    assertTrue(device.wait(Until.hasObject(By.res(app.packageName, "protectionPage")), 10_000))
                    onView(withId(R.id.protectionPage)).check(matches(isDisplayed()))
                } finally {
                    instrumentation.removeMonitor(monitor)
                }
            }
            assertTrue(repository.sent.isEmpty())
            assertTrue(app.sms.isEmpty())
        }
    }

    @Test fun localModelsLeaveOrdinaryLinksSilentAndScamLanguageAsACaution() = runBlocking {
        assertFalse(app.settings.state.value.enhanced)
        assertFalse(app.demoEnabled.value)
        val marker = "private_body_integration_92481"
        val safe = app.processMessage("$marker https://google.com", "integration", simulate = true)
        assertTrue(safe.available)
        assertTrue(safe.results.isNotEmpty())
        assertTrue(safe.results.all { it.verdict.level == Level.SAFE })

        // Inert example: model evidence is a caution, never a verified URL threat.
        val warning = app.processMessage(SCAM_SAMPLE, "integration", simulate = true)
        assertTrue(warning.available)
        assertTrue(warning.results.any { it.verdict.level == Level.SUSPICIOUS })
        assertTrue(warning.results.none { it.verdict.level == Level.DANGEROUS })
        assertTrue(warning.results.none { ReasonCode.DEMO_ONLY in it.verdict.reasonCodes })
        withTimeout(15_000) { app.history.first { entries -> entries.any { it.level == Level.SUSPICIOUS.name } } }
        assertFalse(app.history.value.toString().contains(marker))
        assertTrue("Guardian SMS remains opt-in", app.sms.isEmpty())
    }

    @Test fun backgroundLocalCautionDoesNotCoverAnEmergencyPopup() = runBlocking {
        if (Build.VERSION.SDK_INT >= 33) {
            device.executeShellCommand("pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        }
        val manager = app.getSystemService(NotificationManager::class.java)
        assertTrue("The notification fallback needs permission for this test", manager.areNotificationsEnabled())
        val monitor = instrumentation.addMonitor("org.suraksha.kavach.AlertActivity", null, false)
        val intent = Intent(app, AlertPopupActivity::class.java)
            .putExtra("alertId", "integration-priority")
            .putExtra("senderName", "Priority test")
        try {
            // Opening a popup directly does not start a siren, look up a location, or send an SOS.
            ActivityScenario.launch<AlertPopupActivity>(intent).use { scenario ->
                onView(withId(R.id.tvSenderName)).check(matches(withText("Priority test")))
                val warning = app.processMessage(SCAM_SAMPLE, "integration-background", simulate = false)
                assertTrue(warning.available)
                assertTrue(warning.results.any { it.verdict.level == Level.SUSPICIOUS })
                assertTrue(warning.results.none { it.verdict.level == Level.DANGEROUS })
                withTimeout(10_000) {
                    while (manager.activeNotifications.none { it.id == AlertDispatcher.CAUTION_ID }) delay(50)
                }
                val notification = manager.activeNotifications.single { it.id == AlertDispatcher.CAUTION_ID }.notification
                assertNull("Scam detection must not replace the emergency popup", notification.fullScreenIntent)
                assertNull("The fallback must not add audio over an emergency", notification.sound)
                assertNull(notification.vibrate)
                instrumentation.waitForIdleSync()
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                onView(withId(R.id.tvSenderName)).check(matches(isDisplayed()))
                assertEquals("No scam warning activity may steal the foreground", 0, monitor.hits)
                assertTrue(app.sms.isEmpty())
                assertTrue(repository.sent.isEmpty())
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun sharingTextOpensAnEditableScanWithoutAutomaticallyScanningOrSending() {
        val payload = "https://example.com/read-only-share"
        val monitor = instrumentation.addMonitor(ProtectionActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    host.startActivity(Intent(host, ShareFileActivity::class.java)
                        .setAction(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, payload))
                }
                val activity = monitor.waitForActivityWithTimeout(10_000)
                assertNotNull("Shared text must open the message scanner", activity)
                assertEquals("CHECK", activity!!.intent.getStringExtra("page"))
                assertTrue("Shared text must be available for review before scanning",
                    device.wait(Until.hasObject(By.text(payload)), 10_000))
                instrumentation.runOnMainSync {
                    assertFalse("Shared text must be removed from the routing intent after consumption",
                        activity.intent.hasExtra(ProtectionActivity.EXTRA_SHARED_MESSAGE))
                }
                assertTrue("Sharing alone must not create a scan record", app.history.value.isEmpty())
                assertTrue(app.sms.isEmpty())
                assertTrue(repository.sent.isEmpty())
                val back = device.wait(Until.findObject(By.text(app.getString(ProtectionR.string.sk_back_to_dontmissmom))), 10_000)
                assertNotNull(back)
                back!!.click()
                onView(withId(R.id.homePage)).check(matches(isDisplayed()))
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun clearingProtectionKeepsHostSessionEmergencySettingsAndHistory() = runBlocking {
        val hostPreferences = FirstLaunchPermissions.preferences(app)
        hostPreferences.edit().putString("integration_host_session", repository.currentUid).commit()
        SirenSettings(app).mute(permanent = true)
        val hostFile = File(app.filesDir, "host-session-integration.txt")
        withContext(Dispatchers.IO) { hostFile.writeText("host-session-preserved") }
        val savedHistory = EmergencyHistory("retained", "alert", "mom", "Mom", "+919876543210",
            null, null, null, System.currentTimeMillis())
        repository.history = listOf(savedHistory)

        assertTrue(app.guardian.configure("+919876543210", enabled = false))
        assertTrue(app.familyCode.save("green mango"))
        app.settings.setRetention(30)
        app.settings.setEnhanced(true)
        app.processMessage("https://google.com", "integration", simulate = true)
        withTimeout(15_000) { app.history.first { it.isNotEmpty() } }

        assertTrue(app.clearProtectionData())
        awaitReady()
        assertFalse("A reset must not silently resume automatic scanning", app.settings.state.value.protectionEnabled)
        assertFalse(app.settings.state.value.setupComplete)
        assertFalse(app.settings.state.value.enhanced)
        assertEquals(7, app.settings.state.value.retentionDays)
        assertTrue(app.history.value.isEmpty())
        assertNull(app.guardian.number())
        assertFalse(app.guardian.state.value.enabled)
        assertFalse(app.familyCode.configured())
        assertTrue(FirstLaunchPermissions.isComplete(app))
        assertEquals(repository.currentUid, hostPreferences.getString("integration_host_session", null))
        assertFalse(SirenSettings(app).enabled())
        assertTrue(SirenSettings(app).permanent())
        assertEquals("host-session-preserved", withContext(Dispatchers.IO) { hostFile.readText() })
        assertEquals(listOf(savedHistory), repository.history)

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.homePage)).check(matches(isDisplayed()))
            onView(withId(R.id.nav_history)).perform(click())
            onView(withId(R.id.historyPage)).check(matches(isDisplayed()))
            onView(withId(R.id.historyName)).check(matches(isDisplayed()))
        }
        assertTrue(app.sms.isEmpty())
    }

    private suspend fun awaitReady() {
        withTimeout(60_000) {
            assertEquals(LoadState.READY, app.modelState.first { it != LoadState.LOADING })
            assertEquals(LoadState.READY, app.storageState.first { it != LoadState.LOADING })
        }
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        device.takeScreenshot(File(app.getExternalFilesDir(null), "$name.png"))
    }

    companion object {
        private const val SCAM_SAMPLE = "Your account will be blocked. Share your OTP immediately to verify your account " +
            "https://ordinary.example/"
    }
}
