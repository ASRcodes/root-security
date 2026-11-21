package com.asrcodes.dontmissmom

import android.Manifest
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
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
import org.suraksha.core.Level
import org.suraksha.core.DemoExamples
import org.suraksha.core.ReasonCode
import org.suraksha.kavach.LoadState
import org.suraksha.kavach.notifications.AlertDispatcher
import org.suraksha.kavach.MainActivity as ProtectionActivity
import org.suraksha.kavach.R as ProtectionR

/**
 * Emulator-only. Drives the same entry point the notification listener uses for an incoming
 * SMS/WhatsApp message (processMessage, not a manual scan) and checks the user-visible outcome:
 * normal links stay silent, a reserved caution example stays non-intrusive, and a reserved
 * high-risk example opens an explicitly labelled local-rule warning. No actual message is sent
 * and this does not establish a real SMS/WhatsApp notification or Google service integration.
 */
@RunWith(AndroidJUnit4::class)
class ScamAlarmEndToEndTest {
    private val app get() = ApplicationProvider.getApplicationContext<DmmTestApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val ui by lazy { RootSecurityUiHarness(app) }
    private var isolatedRunStarted = false

    @Before fun setup(): Unit = runBlocking {
        assumeTrue("This suite resets state and must only run on an emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        isolatedRunStarted = true
        if (Build.VERSION.SDK_INT >= 33) UiDevice.getInstance(instrumentation)
            .executeShellCommand("pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        assertTrue(app.clearProtectionData())
        withTimeout(60_000) {
            assertEquals(LoadState.READY, app.modelState.first { it != LoadState.LOADING })
            assertEquals(LoadState.READY, app.storageState.first { it != LoadState.LOADING })
        }
        app.settings.setLanguage("en")
        app.settings.finishSetup()
        app.settings.setProtection(true)
        app.settings.setVoice(false)
        app.settings.setOverlay(false)
        app.sms.clear()
        app.reputationRequests.clear()
    }

    @After fun cleanup() { if (isolatedRunStarted) app.dismissWarnings() }

    @Test fun normalAndCautionInputsStayNonIntrusiveWhileReservedHighRiskShowsLabelledWarning(): Unit = runBlocking {
        val manager = app.getSystemService(NotificationManager::class.java)
        ActivityScenario.launch(ProtectionActivity::class.java).use {
            val genuine = listOf(
                DemoExamples.NORMAL_URL,
                DemoExamples.AIRTEL_URL,
                DemoExamples.GOOD_MONK_URL
            )
            val monitor = instrumentation.addMonitor("org.suraksha.kavach.AlertActivity", null, false)
            try {
                genuine.forEachIndexed { index, message ->
                    val outcome = app.processMessage(message, "e2e-genuine-$index")
                    assertTrue(message, outcome.available)
                    assertTrue(message, outcome.results.all { it.verdict.level == Level.SAFE })
                    assertTrue("Offline must not be called a clean provider result", outcome.results.all {
                        ReasonCode.REPUTATION_NOT_CHECKED in it.verdict.reasonCodes &&
                            ReasonCode.REPUTATION_NO_MATCH !in it.verdict.reasonCodes
                    })
                }
                delay(500)
                assertNull("A normal link opened an alarm", monitor.lastActivity)
                assertTrue(manager.activeNotifications.none { it.id == AlertDispatcher.ID })
                val caution = app.processMessage(DemoExamples.SUSPICIOUS_URL, "e2e-caution").results.single()
                assertEquals(Level.SUSPICIOUS, caution.verdict.level)
                assertTrue(ReasonCode.TEST_INPUT in caution.verdict.reasonCodes)
                delay(500)
                assertEquals("Caution must not launch a full-screen warning", 0, monitor.hits)

                assertTrue(app.guardian.configure("+12025550142", enabled = true))
                val outcome = app.processMessage(DemoExamples.HIGH_RISK_MESSAGE, "e2e-local-example")
                val danger = outcome.results.single()
                assertEquals(Level.DANGEROUS, danger.verdict.level)
                assertTrue(ReasonCode.LOCAL_RISK in danger.verdict.reasonCodes)
                assertTrue(ReasonCode.TEST_INPUT in danger.verdict.reasonCodes)
                assertFalse(ReasonCode.GOOGLE_REPUTATION in danger.verdict.reasonCodes)
                assertFalse(ReasonCode.DEMO_ONLY in danger.verdict.reasonCodes)
                val alert = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
                assertNotNull("The high-risk local example did not open its warning", alert)
                assertTrue(device.wait(Until.hasObject(By.pkg(app.packageName)
                    .text(app.getString(ProtectionR.string.sk_demo_reserved_badge))), 10_000))
                assertTrue(device.hasObject(By.pkg(app.packageName).text(app.getString(ProtectionR.string.sk_demo_danger_title))))
                assertFalse(device.hasObject(By.pkg(app.packageName).text(app.getString(ProtectionR.string.sk_call_guardian))))
                alert.finish()
                withTimeout(15_000) { app.history.first { entries -> entries.any { it.level == Level.DANGEROUS.name } } }
                assertTrue("Harmless local examples must never send guardian SMS", app.sms.isEmpty())
                assertTrue("These offline checks must not contact a provider", app.reputationRequests.isEmpty())
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }
    }

    @Test fun selectingAndCopyingAnExampleShowsItsExpectedBehaviorWithoutScanning(): Unit = runBlocking {
        val before = app.history.value.toList()
        val monitor = instrumentation.addMonitor("org.suraksha.kavach.AlertActivity", null, false)
        try {
            ActivityScenario.launch<ProtectionActivity>(Intent(app, ProtectionActivity::class.java).putExtra("page", "DEBUG")).use {
                val demoTitle = app.getString(ProtectionR.string.sk_demo_lab)
                assertEquals(demoTitle, ui.findText(demoTitle, towardBottom = false).text)
                ui.findText(app.getString(ProtectionR.string.sk_demo_danger_label)).click()
                ui.findText(DemoExamples.HIGH_RISK_MESSAGE)
                ui.findText(app.getString(ProtectionR.string.sk_demo_danger_expected))
                ui.findText(app.getString(ProtectionR.string.sk_demo_copy)).click()
                var copied: String? = null
                instrumentation.runOnMainSync {
                    copied = app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
                }
                assertEquals(DemoExamples.HIGH_RISK_MESSAGE, copied)
                assertEquals(0, monitor.hits)
                assertEquals(before, app.history.value)
                assertTrue(app.sms.isEmpty())
                assertTrue(app.reputationRequests.isEmpty())
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
