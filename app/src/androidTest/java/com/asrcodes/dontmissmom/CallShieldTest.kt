package com.asrcodes.dontmissmom

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.suraksha.core.CallReason
import org.suraksha.core.CallRisk
import org.suraksha.core.CallRiskLevel
import org.suraksha.core.CallVerdict
import org.suraksha.kavach.calls.CallAlerts
import org.suraksha.kavach.calls.CallShieldPlatform
import org.suraksha.kavach.calls.ReportCallerReceiver
import org.suraksha.kavach.data.AppSettings
import java.util.UUID

/** Emulator only: no calls are placed. Exercises local reports and the real notification action. */
@RunWith(AndroidJUnit4::class)
class CallShieldTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<DmmTestApplication>()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val device get() = UiDevice.getInstance(instrumentation)
    private var roleAdded = false
    private var initialized = false

    @Before fun setup() {
        assumeTrue("This suite must not modify a personal phone", Build.HARDWARE in setOf("ranchu", "goldfish") ||
            Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("Emulator"))
        assumeTrue("Call-screening role requires Android 10+", Build.VERSION.SDK_INT >= 29)
        if (Build.VERSION.SDK_INT >= 33) device.executeShellCommand(
            "pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        if (!CallShieldPlatform.roleHeld(app)) {
            device.executeShellCommand("cmd role add-role-holder --user 0 android.app.role.CALL_SCREENING ${app.packageName}")
            repeat(50) { if (!CallShieldPlatform.roleHeld(app)) Thread.sleep(100) }
            assertTrue("Emulator must grant the call-screening role", CallShieldPlatform.roleHeld(app))
            roleAdded = true
        }
        initialized = true
        app.settings.setLanguage("en")
        app.settings.setCallShield(true)
        app.settings.setOverlay(false)
        app.settings.clearReportedNumbers()
        CallAlerts.dismissAll(app)
        instrumentation.waitForIdleSync()
    }

    @After fun cleanup() {
        if (!initialized) return
        app.settings.setCallShield(false)
        app.settings.clearReportedNumbers()
        CallAlerts.dismissAll(app)
        if (roleAdded) device.executeShellCommand(
            "cmd role remove-role-holder --user 0 android.app.role.CALL_SCREENING ${app.packageName}")
    }

    private fun waitForWarning(): Notification {
        repeat(50) {
            manager.activeNotifications.firstOrNull { it.id == CallAlerts.ID }?.let { return it.notification }
            Thread.sleep(100)
        }
        fail("No call caution was posted"); throw IllegalStateException()
    }

    private fun postUnknown(number: String = "+919812345678"): Notification {
        CallAlerts.show(app, number, CallVerdict(CallRiskLevel.CAUTION, setOf(CallReason.UNKNOWN_CALLER)))
        return waitForWarning()
    }

    private fun settle() { instrumentation.waitForIdleSync(); Thread.sleep(300) }

    @Test fun unknownCallerWarnsAndReportEscalatesTheNextCall() {
        val number = "+919812345678"
        val caution = postUnknown(number)
        assertEquals("Check this caller", caution.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(caution.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("not necessarily a scam"))
        val report = caution.actions.single()
        assertEquals("Report this number", report.title.toString())
        report.actionIntent.send()
        repeat(50) { if (number !in app.settings.state.value.reportedNumbers) Thread.sleep(100) }
        assertTrue(number in app.settings.state.value.reportedNumbers)
        settle()
        val second = CallRisk.evaluate("09812345678", hidden = false, knownContact = false,
            reported = app.settings.state.value.reportedNumbers)
        assertEquals(setOf(CallReason.REPORTED_NUMBER), second.reasons)
        CallAlerts.show(app, "09812345678", second)
        val reported = waitForWarning()
        assertEquals("Number you reported", reported.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(reported.actions.isNullOrEmpty())
    }

    @Test fun pauseInvalidatesAnEarlierNotificationReportEvenAfterReenable() {
        val oldAction = postUnknown().actions.single().actionIntent
        app.settings.setCallShield(false)
        CallAlerts.dismissAll(app)
        oldAction.send()
        settle()
        assertTrue(app.settings.state.value.reportedNumbers.isEmpty())
        app.settings.setCallShield(true)
        oldAction.send()
        settle()
        assertTrue(app.settings.state.value.reportedNumbers.isEmpty())
    }

    @Test fun resetKeepsCallShieldOffAfterReloadAndAdvancesTheActionEpoch() {
        val name = "call-shield-reset-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(name, mode)
        }
        val settings = AppSettings(context)
        try {
            settings.setCallShield(true)
            assertTrue(settings.reportNumber("+919812345678"))
            val oldEpoch = settings.state.value.callActionEpoch
            settings.reset()
            val reloaded = AppSettings(context).state.value
            assertFalse(reloaded.callShieldEnabled)
            assertFalse(reloaded.protectionEnabled)
            assertTrue(reloaded.callActionEpoch > oldEpoch)
            assertTrue(reloaded.reportedNumbers.isEmpty())
        } finally { app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun pausedShieldAndNoRiskVerdictNeverPostWarnings() {
        app.settings.setCallShield(false)
        CallAlerts.show(app, "+919812345678", CallVerdict(CallRiskLevel.CAUTION, setOf(CallReason.UNKNOWN_CALLER)))
        settle()
        assertFalse(manager.activeNotifications.any { it.id == CallAlerts.ID })
        app.settings.setCallShield(true)
        CallAlerts.show(app, "+919812345678", CallVerdict(CallRiskLevel.NONE, emptySet()))
        settle()
        assertFalse(manager.activeNotifications.any { it.id == CallAlerts.ID })
    }

    @Test fun expiredNotificationActionCannotCreateALocalReport() {
        val intent = Intent(app, ReportCallerReceiver::class.java)
            .putExtra(ReportCallerReceiver.EXTRA_NUMBER, "+919812345678")
            .putExtra(ReportCallerReceiver.EXTRA_EPOCH, app.settings.state.value.callActionEpoch)
            .putExtra(ReportCallerReceiver.EXTRA_ISSUED_AT, System.currentTimeMillis() - CallAlerts.REPORT_ACTION_MILLIS - 1)
        ReportCallerReceiver().onReceive(app, intent)
        assertTrue(app.settings.state.value.reportedNumbers.isEmpty())
    }

    @Test fun reportsAreNormalizedRemovableAndBoundedWithoutClearingOtherSettings() {
        val name = "call-shield-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(name, mode)
        }
        val settings = AppSettings(context)
        try {
            settings.setLanguage("hi")
            assertTrue(settings.reportNumber("+91 98123-45678"))
            assertTrue(settings.reportNumber("00919812345678"))
            assertEquals(setOf("+919812345678"), settings.state.value.reportedNumbers)
            settings.removeReportedNumber("+91 98123 45678")
            assertTrue(settings.state.value.reportedNumbers.isEmpty())
            repeat(AppSettings.MAX_REPORTED_NUMBERS) { assertTrue(settings.reportNumber("+919800%06d".format(it))) }
            assertEquals(200, settings.state.value.reportedNumbers.size)
            assertFalse(settings.reportNumber("+919899999999"))
            assertFalse(settings.reportNumber("not-a-number"))
            settings.clearReportedNumbers()
            assertTrue(settings.state.value.reportedNumbers.isEmpty())
            assertEquals("hi", settings.state.value.language)
        } finally { app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }
}
