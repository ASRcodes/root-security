package com.asrcodes.dontmissmom

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.role.RoleManager
import android.os.Build
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.suraksha.kavach.calls.CallAlerts
import org.suraksha.kavach.R as ProtectionR

/** Explicit emulator radio rehearsal. The host injects `adb emu gsm call`; no real call is placed. */
@RunWith(AndroidJUnit4::class)
class RootSecurityCallRadioTest {
    @Test fun simulatedRadioCallReachesAndroidScreeningAndShowsTheLocalReport(): Unit = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish") && Build.VERSION.SDK_INT >= 29)
        assumeTrue(InstrumentationRegistry.getArguments().getString("rootSecurityRadio") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<DmmTestApplication>()
        val device = UiDevice.getInstance(instrumentation)
        val manager = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33) device.executeShellCommand("pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        device.executeShellCommand("cmd role add-role-holder --user 0 android.app.role.CALL_SCREENING ${app.packageName}")
        assertTrue(app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING))
        app.settings.setLanguage("en")
        app.settings.setOverlay(false)
        app.settings.setCallShield(true)
        app.settings.reportNumber("+12025550142")
        CallAlerts.dismissAll(app)
        instrumentation.waitForIdleSync()
        try {
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "ROOT_SECURITY_RADIO_READY +12025550142 — emulator simulation only\n")
            })
            val warning = withTimeout(60_000) {
                while (true) {
                    val posted = manager.activeNotifications.firstOrNull { it.id == CallAlerts.ID }?.notification
                    if (posted != null) return@withTimeout posted
                    delay(150)
                }
                @Suppress("UNREACHABLE_CODE")
                error("No call notification")
            }
            assertEquals(app.getString(ProtectionR.string.sk_call_title_high), warning.extras.getString(Notification.EXTRA_TITLE))
            assertTrue(warning.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
                .contains(app.getString(ProtectionR.string.sk_call_reason_reported)))
            assertNull("Call Shield must not replace the dialer's incoming-call screen", warning.fullScreenIntent)
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "ROOT_SECURITY_RADIO_OBSERVED local reported-number warning from Android callback\n")
            })
        } finally {
            app.settings.setCallShield(false)
            CallAlerts.dismissAll(app)
        }
    }
}
