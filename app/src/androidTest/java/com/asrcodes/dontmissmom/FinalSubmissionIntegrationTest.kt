package com.asrcodes.dontmissmom

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.suraksha.core.*
import org.suraksha.kavach.LoadState
import org.suraksha.kavach.calls.CallAlerts
import org.suraksha.kavach.calls.CallShieldPlatform
import org.suraksha.kavach.data.SecretStore
import org.suraksha.kavach.family.GuardianManager
import org.suraksha.kavach.family.SmsSender
import java.util.UUID
import org.suraksha.kavach.R as ProtectionR

/** Emulator-only, fixture transport and fake SMS. Never execute on a personal phone. */
@RunWith(AndroidJUnit4::class)
class FinalSubmissionIntegrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<DmmTestApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private var initialized = false
    private lateinit var repository: FakeRepository

    @Before fun setup(): Unit = runBlocking {
        assumeTrue("Emulator only: this suite resets protection state", Build.HARDWARE in setOf("ranchu", "goldfish"))
        initialized = true
        repository = FakeRepository()
        AppDependencies.repositoryFactory = { repository }
        FirstLaunchPermissions.preferences(app).edit()
            .putBoolean(FirstLaunchPermissions.COMPLETE, true)
            .putBoolean(FirstLaunchPermissions.NOTICE_PENDING, false).commit()
        withTimeout(60_000) {
            assertEquals(LoadState.READY, app.modelState.first { it != LoadState.LOADING })
            assertEquals(LoadState.READY, app.storageState.first { it != LoadState.LOADING })
        }
        assertTrue(app.clearProtectionData())
        app.settings.setLanguage("en")
        app.settings.finishSetup()
        app.settings.setProtection(true)
        app.settings.setVoice(false)
        app.settings.setOverlay(false)
        app.sms.clear()
        app.reputationRequests.clear()
        app.reputationResponder = { "{\"cacheDuration\":\"300s\",\"fullHashes\":[]}" }
        assertTrue(app.online.configure(DmmTestApplication.REPUTATION_FIXTURE_KEY))
    }

    @After fun cleanup(): Unit = runBlocking {
        if (initialized) {
            app.dismissWarnings()
            app.settings.setCallShield(false)
            CallAlerts.dismissAll(app)
            assertTrue(app.clearProtectionData())
            AppDependencies.repositoryFactory = null
        }
    }

    @Test fun reservedExamplesStayLocalEvenWhenOnlineCheckingIsConfigured(): Unit = runBlocking {
        assertTrue(app.online.state.value.enabled)
        assertTrue(app.online.state.value.keyConfigured)
        val caution = app.processMessage(DemoExamples.SUSPICIOUS_URL, "final-example-caution", simulate = true)
            .results.single()
        val high = app.processMessage(DemoExamples.HIGH_RISK_MESSAGE, "final-example-high", simulate = true)
            .results.single()
        assertEquals(Level.SUSPICIOUS, caution.verdict.level)
        assertEquals(Level.DANGEROUS, high.verdict.level)
        assertTrue(ReasonCode.LOCAL_RISK in high.verdict.reasonCodes)
        listOf(caution, high).forEach {
            assertTrue(ReasonCode.TEST_INPUT in it.verdict.reasonCodes)
            assertFalse(ReasonCode.GOOGLE_REPUTATION in it.verdict.reasonCodes)
            assertFalse(ReasonCode.REPUTATION_NO_MATCH in it.verdict.reasonCodes)
        }
        assertTrue("Reserved examples must not reach even the fixture transport", app.reputationRequests.isEmpty())
        val normal = app.processMessage(DemoExamples.NORMAL_URL, "final-normal-online-control", simulate = true)
            .results.single()
        assertEquals(Level.SAFE, normal.verdict.level)
        assertTrue(ReasonCode.REPUTATION_NO_MATCH in normal.verdict.reasonCodes)
        assertFalse(ReasonCode.TEST_INPUT in normal.verdict.reasonCodes)
        assertTrue("A real URL still uses the configured provider path", app.reputationRequests.isNotEmpty())
        assertTrue(app.sms.isEmpty())
    }

    @Test fun enabledGuardianWithGrantedPermissionNeverSendsForTestInput(): Unit = runBlocking {
        val prefix = "final_guardian_fixture_${UUID.randomUUID()}_"
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefix + name, mode)
        }
        val sent = mutableListOf<Pair<String, String>>()
        val guardian = GuardianManager(context, SecretStore(context),
            SmsSender { destination, message, _ -> sent += destination to message }, permission = { true })
        try {
            assertTrue(guardian.configure("+12025550142", enabled = true))
            assertTrue(guardian.state.value.enabled)
            val now = System.currentTimeMillis()
            val example = ScanResult("sbi-verify.example", Verdict(Level.DANGEROUS, 0.9,
                setOf(ReasonCode.LOCAL_RISK, ReasonCode.TEST_INPUT)), now)
            guardian.onDanger(example)
            assertTrue("TEST_INPUT must suppress SMS despite permission and opt-in", sent.isEmpty())
            // Positive control proves this fixture could send; it never reaches a carrier.
            guardian.onDanger(ScanResult("control.invalid", Verdict(Level.DANGEROUS, 0.9,
                setOf(ReasonCode.LOCAL_RISK)), now))
            assertEquals(1, sent.size)
        } finally { guardian.reset() }
    }

    @Test fun activeSosSuppressesCallWarningsWithRoleAndOverlayPermissionGranted(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val roleWasHeld = CallShieldPlatform.roleHeld(app)
        val overlayWasAllowed = Settings.canDrawOverlays(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33) device.executeShellCommand(
            "pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        try {
            if (!roleWasHeld) device.executeShellCommand(
                "cmd role add-role-holder --user 0 android.app.role.CALL_SCREENING ${app.packageName}")
            repeat(50) { if (!CallShieldPlatform.roleHeld(app)) delay(100) }
            assertTrue("Call-screening role must be active for this regression", CallShieldPlatform.roleHeld(app))
            if (!overlayWasAllowed) device.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
            assertTrue("OS overlay permission must be present so it cannot mask this regression", Settings.canDrawOverlays(app))
            assertTrue(manager.areNotificationsEnabled())
            app.settings.setCallShield(true)
            app.settings.setOverlay(true)
            val number = "+919812345678"
            assertTrue(app.settings.reportNumber(number))
            val verdict = CallRisk.evaluate(number, hidden = false, knownContact = false,
                reported = app.settings.state.value.reportedNumbers)
            assertEquals(CallRiskLevel.HIGH, verdict.level)
            val monitor = instrumentation.addMonitor("org.suraksha.kavach.AlertActivity", null, false)
            try {
                ActivityScenario.launch<AlertPopupActivity>(Intent(app, AlertPopupActivity::class.java)
                    .putExtra("alertId", "final-call-priority")
                    .putExtra("senderName", "Call priority test")).use { scenario ->
                    onView(withId(R.id.tvSenderName)).check(matches(withText("Call priority test")))
                    assertFalse(app.canShowIntrusiveWarnings())
                    CallAlerts.show(app, number, verdict)
                    instrumentation.waitForIdleSync()
                    delay(500)
                    assertTrue("Call notification must not interrupt SOS", manager.activeNotifications.none { it.id == CallAlerts.ID })
                    assertFalse("Call overlay must not cover SOS", device.hasObject(By.pkg(app.packageName)
                        .text(app.getString(ProtectionR.string.sk_call_title_high))))
                    assertEquals(Lifecycle.State.RESUMED, scenario.state)
                    onView(withId(R.id.tvSenderName)).check(matches(isDisplayed()))
                    assertEquals(0, monitor.hits)
                    assertTrue(repository.sent.isEmpty())
                    assertTrue(app.sms.isEmpty())
                }
            } finally { instrumentation.removeMonitor(monitor) }
        } finally {
            app.settings.setCallShield(false)
            app.settings.setOverlay(false)
            CallAlerts.dismissAll(app)
            if (!roleWasHeld) device.executeShellCommand(
                "cmd role remove-role-holder --user 0 android.app.role.CALL_SCREENING ${app.packageName}")
            if (!overlayWasAllowed) device.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW default")
        }
    }
}
