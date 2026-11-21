package org.suraksha.kavach

import android.app.KeyguardManager
import android.app.NotificationManager
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.suraksha.core.Level
import org.suraksha.kavach.notifications.AlertDispatcher

/**
 * Emulator only: after readiness inject an OTP-pressure SMS that carries an ordinary link, e.g.
 * "Your account will be blocked. Share your OTP immediately to verify your account https://ordinary.example/".
 * (Without a link, credential pressure is a full alarm.) No real number is contacted.
 */
@RunWith(AndroidJUnit4::class)
class NativeNotificationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun virtualSmsLocalPatternProducesCautionWithoutFullScreen() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("native_sms") == "true")
        val app = compose.activity.application as KavachTestApplication
        runBlocking { withTimeout(60_000) { while (app.modelState.value == LoadState.LOADING || !app.listenerConnected.value) delay(100) } }
        app.setDemo(false); app.settings.setProtection(true); app.settings.setLanguage("en")
        val locked = InstrumentationRegistry.getArguments().getString("native_locked") == "true"
        val previous = app.settings.state.value
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val notifications = app.getSystemService(NotificationManager::class.java)
        fun status(message: String) = instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$message\n") })
        try {
            app.settings.setOverlay(false)
            app.settings.setVoice(true)
            compose.runOnIdle { app.overlays.dismiss(); app.alerts.cancel() }
            if (locked) {
                val keyguard = app.getSystemService(KeyguardManager::class.java)
                assertFalse("Use a dedicated emulator without a PIN", keyguard.isKeyguardSecure)
                status("READY_TO_LOCK_SCREEN")
                compose.waitUntil(60_000) {
                    var noResumedActivity = false
                    instrumentation.runOnMainSync {
                        noResumedActivity = ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED).isEmpty()
                    }
                    keyguard.isKeyguardLocked && noResumedActivity
                }
                status("LOCKED_BACKGROUND_CONFIRMED")
            }
            val started = System.currentTimeMillis()
            status("READY_FOR_EMULATED_SMS")
            compose.waitUntil(60_000) { app.history.value.any { it.timestamp >= started && it.level == Level.SUSPICIOUS.name } }
            compose.waitUntil(15_000) { notifications.activeNotifications.any { it.id == AlertDispatcher.CAUTION_ID } }
            val caution = notifications.activeNotifications.single { it.id == AlertDispatcher.CAUTION_ID }.notification
            assertNull("Local text predictions must not launch a full-screen alarm", caution.fullScreenIntent)
            assertNull(caution.sound)
            assertTrue(notifications.activeNotifications.none { it.id == AlertDispatcher.ID })
            if (locked) status("LOCKED_CAUTION_WITHOUT_FULL_SCREEN_CONFIRMED")
            assertTrue(app.sms.isEmpty())
        } finally {
            app.settings.setOverlay(previous.overlayEnabled)
            app.settings.setVoice(previous.voiceEnabled)
        }
    }
}
