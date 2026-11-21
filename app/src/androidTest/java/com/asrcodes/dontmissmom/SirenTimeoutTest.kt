package com.asrcodes.dontmissmom

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SirenTimeoutTest {
    @Suppress("DEPRECATION")
    @Test fun unansweredSirenStopsAfterFiveRealMinutesAndHistoryRemains() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FirstLaunchPermissions.preferences(context).edit().putBoolean(FirstLaunchPermissions.COMPLETE, true)
            .putBoolean(FirstLaunchPermissions.NOTICE_PENDING, false).commit()
        val repository = FakeRepository().apply {
            history = listOf(EmergencyHistory("timeout_received", "timeout", "mom", "Mom", "+919876543210", 28.6, 77.2, null, System.currentTimeMillis()))
        }
        AppDependencies.repositoryFactory = { repository }
        fun running() = context.getSystemService(ActivityManager::class.java).getRunningServices(50)
            .any { it.service.className == EmergencySoundService::class.java.name }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val started = SystemClock.elapsedRealtime()
                scenario.onActivity { activity ->
                    val alert = IncomingAlert("timeout", "Mom", "+919876543210", null, null, null, "emulator-only-recipient")
                    ContextCompat.startForegroundService(activity, Intent(activity, EmergencySoundService::class.java)
                        .setAction(EmergencySoundService.ACTION_START).putExtras(alert.intent(activity)))
                }
                // Poll in short intervals so a crash or early timeout is detected. No real FCM/account is used.
                SystemClock.sleep(2000)
                assertTrue(running())
                while (SystemClock.elapsedRealtime() - started < SirenPolicy.TIMEOUT_MS - 2000) {
                    assertTrue("Siren stopped before five minutes", running())
                    SystemClock.sleep(1000)
                }
                val deadline = started + SirenPolicy.TIMEOUT_MS + 10_000
                while (running() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(500)
                assertFalse("Siren did not stop after five minutes", running())
                assertEquals("timeout", repository.history.single().alertId)
            }
        } finally {
            AlertDelivery.dismiss(context)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                listOf(androidx.test.runner.lifecycle.Stage.RESUMED, androidx.test.runner.lifecycle.Stage.STARTED, androidx.test.runner.lifecycle.Stage.STOPPED)
                    .flatMap { monitor.getActivitiesInStage(it) }.filterIsInstance<AlertPopupActivity>().forEach { it.finish() }
            }
            AppDependencies.repositoryFactory = null
        }
    }
}
