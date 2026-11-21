package org.suraksha.kavach

import android.app.NotificationManager
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.suraksha.core.Level
import org.suraksha.kavach.data.HistoryEntry
import org.suraksha.kavach.notifications.AlertDispatcher
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhaseTwoDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as KavachApplication

    private fun ready() = runBlocking {
        withTimeout(60_000) { while (app.modelState.value == LoadState.LOADING || app.storageState.value == LoadState.LOADING) delay(100) }
        assertEquals(LoadState.READY, app.modelState.value)
        assertEquals(LoadState.READY, app.storageState.value)
        app.settings.finishSetup()
        compose.onNodeWithText(compose.activity.getString(R.string.sk_home)).performClick()
        app.setDemo(false)
        app.alerts.cancel()
    }

    // With an ordinary link the wording alone is a caution; without a link it would be an alarm.
    private fun warningSample() = "Your account will be blocked. Share your OTP immediately to verify your account https://ordinary.example/"

    @Test fun realModelsStaySilentForTrustedLinksAndEncryptOnlyMetadata() = runBlocking {
        ready()
        val started = System.currentTimeMillis()
        val secretMarker = "private_body_should_never_be_saved_73415"
        for (domain in listOf("google.com", "youtube.com", "wikipedia.org")) {
            val result = app.processMessage("$secretMarker https://$domain", "instrumentation", true)
            assertTrue(result.available)
            assertTrue(result.results.isNotEmpty())
            assertTrue(result.results.all { it.verdict.level == Level.SAFE })
        }
        withTimeout(15_000) {
            while (!app.history.value.filter { it.timestamp >= started }.map { it.domain }.containsAll(listOf("google.com", "youtube.com", "wikipedia.org"))) delay(100)
        }
        val notifications = app.getSystemService(NotificationManager::class.java).activeNotifications
        assertTrue(notifications.none { it.id == AlertDispatcher.ID || it.id == AlertDispatcher.CAUTION_ID })
        val warning = app.processMessage(warningSample(), "instrumentation", true)
        assertTrue(warning.results.any { it.verdict.level == Level.SUSPICIOUS })
        assertTrue(warning.results.none { it.verdict.level == Level.DANGEROUS })
        withTimeout(15_000) { while (app.history.value.none { it.level == Level.SUSPICIOUS.name }) delay(100) }
        val db = requireNotNull(app.databaseForTests())
        val columns = mutableSetOf<String>()
        db.openHelper.readableDatabase.query("PRAGMA table_info(activity)").use {
            while (it.moveToNext()) columns += it.getString(1)
        }
        assertEquals(setOf("level", "domain", "timestamp", "reasonCodes"), columns)
        db.openHelper.readableDatabase.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
        val databaseFiles = app.getDatabasePath("kavach_activity.db").parentFile!!.listFiles().orEmpty()
        for (file in databaseFiles) {
            val bytes = file.readBytes()
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(secretMarker))
            if (file.name == "kavach_activity.db") assertFalse(bytes.take(16).toByteArray().toString(Charsets.US_ASCII).startsWith("SQLite format 3"))
        }
        capture("phase2-home")
    }

    @Test fun strongLocalScamLanguageShowsCautionWithoutARedScreen() {
        ready()
        compose.onNodeWithText(compose.activity.getString(R.string.sk_settings)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.sk_demo_lab)).performScrollTo().performClick()
        compose.onNodeWithTag("messageInput").performTextInput(warningSample())
        compose.onNodeWithTag("scanButton").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("manualOutcome").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.sk_online_unconfirmed_pattern)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.sk_alert_title)).assertDoesNotExist()
        capture("phase2-caution")
    }

    @Test fun oldActivityIsPrunedWithoutSavingMessageContent() = runBlocking {
        ready()
        val db = requireNotNull(app.databaseForTests())
        val old = System.currentTimeMillis() - 32 * 86_400_000L
        db.history().insert(listOf(HistoryEntry("SAFE", "retention-test.example", old, "")))
        app.pruneHistory()
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM activity WHERE domain = 'retention-test.example'").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(app.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
