package com.asrcodes.dontmissmom

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class V2UiTest {
    private lateinit var repository: FakeRepository
    @Before fun setup() {
        repository = FakeRepository()
        AppDependencies.repositoryFactory = { repository }
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(LocationDisclosureActivity.PREFS_NAME, 0)
            .edit().putBoolean("location_disclosure_accepted", false)
            .putBoolean(FirstLaunchPermissions.COMPLETE, true).putBoolean(FirstLaunchPermissions.NOTICE_PENDING, false).commit()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO) }
    }
    @After fun cleanup() { AppDependencies.repositoryFactory = null }

    @Test fun bottomNavigationBackAndRotationKeepTheCorrectPage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.nav_circle)).perform(click())
            onView(withId(R.id.circlePage)).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withId(R.id.circlePage)).check(matches(isDisplayed()))
            onView(withId(R.id.nav_settings)).perform(click())
            onView(withId(R.id.settingsPage)).check(matches(isDisplayed()))
            pressBack()
            onView(withId(R.id.homePage)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val navigation = activity.findViewById<View>(R.id.bottomNavigation)
                val rect = Rect(); navigation.getGlobalVisibleRect(rect)
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)!!.getInsets(WindowInsetsCompat.Type.systemBars())
                assertTrue("Navigation overlaps system buttons", rect.bottom <= activity.window.decorView.height - insets.bottom)
            }
        }
    }

    @Test fun emptyCircleAndInvalidPhoneHaveActionableStates() {
        repository.contacts = emptyList()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.nav_circle)).perform(click())
            onView(withText(R.string.empty_circle_title)).check(matches(isDisplayed()))
            onView(withId(R.id.btnAddContact)).perform(click())
            onView(withId(R.id.dialogEditText)).perform(typeText("123"), closeSoftKeyboard())
            onView(withText(R.string.send_request)).perform(click())
            onView(withText(R.string.phone_invalid)).check(matches(isDisplayed()))
            assertEquals(0, repository.addCalls)
        }
    }

    @Test fun alertAllCapturesVoiceAndWaitsForEveryBackendResult() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[MainViewModel::class.java]
                val voice = "https://res.cloudinary.com/dqrb7jasx/raw/upload/v1/sos_voice_memos/test.3gp"
                model.voice.value = VoiceState("ready", url = voice)
                model.sendTo(repository.contacts)
                assertEquals(2, repository.sent.size)
                assertTrue(repository.sent.all { it.second.voiceUrl == voice })
                assertTrue(model.sending.value == true)
                assertNull(model.outcome.value)
                repository.completions[0](Result.success(Unit))
                assertTrue(model.sending.value == true)
                assertEquals(voice, model.voice.value?.url)
                repository.completions[1](Result.success(Unit))
                assertFalse(model.sending.value == true)
                assertNull(model.voice.value?.url)
            }
        }
    }

    @Test fun blockedContactsAreExcludedAndFailedSendKeepsVoice() {
        repository.contacts = repository.contacts + TrustedContact("blocked", "Blocked", "+919111111111", true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[MainViewModel::class.java]
                model.voice.value = VoiceState("ready", url = "recording")
                model.sendTo(repository.contacts)
                assertEquals(2, repository.sent.size)
                repository.completions[0](Result.success(Unit))
                repository.completions[1](Result.failure(Exception("offline")))
                assertEquals("recording", model.voice.value?.url)
                assertFalse(model.sending.value == true)
            }
        }
    }

    @Test fun retryOnlySendsUnconfirmedContactsWithOriginalIdsAndAttachment() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[MainViewModel::class.java]
                model.voice.value = VoiceState("ready", url = "original-recording")
                model.sendTo(repository.contacts)
                val failedId = repository.alertIds[1]
                repository.completions[0](Result.success(Unit))
                repository.completions[1](Result.failure(Exception("response lost")))
                model.voice.value = VoiceState("ready", url = "new-recording")
                model.retryFailed()
                assertEquals(3, repository.sent.size)
                assertEquals(repository.contacts[1].uid, repository.sent[2].first.uid)
                assertEquals(failedId, repository.alertIds[2])
                assertEquals("original-recording", repository.sent[2].second.voiceUrl)
                repository.completions[2](Result.success(Unit))
                assertEquals("new-recording", model.voice.value?.url)
                model.retryFailed()
                assertEquals(3, repository.sent.size)
            }
        }
    }

    @Test fun requestAcceptanceRemovesTheCardAndShowsEmptyState() {
        ActivityScenario.launch(RequestsActivity::class.java).use {
            onView(withId(R.id.btnAccept)).perform(click())
            onView(withText(R.string.empty_requests_title)).check(matches(isDisplayed()))
            assertEquals(1, repository.acceptCalls)
        }
    }

    @Test fun contactsSendIndependentlyAndSameContactCannotBeSubmittedTwice() {
        repository.contacts = repository.contacts + TrustedContact("friend", "Friend", "+919876543212")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.nav_circle)).perform(click())
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[MainViewModel::class.java]
                model.sendTo(listOf(repository.contacts[0]))
                val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerTrustedContacts)
                val adapter = recycler.adapter as TrustedContactAdapter
                repository.contacts.indices.forEach { index ->
                    val holder = adapter.createViewHolder(recycler, 0)
                    adapter.bindViewHolder(holder, index)
                    assertEquals(index != 0, holder.alert.isEnabled)
                    if (index == 0) assertEquals(activity.getString(R.string.sending_sos), holder.alert.text.toString())
                }
                model.sendTo(listOf(repository.contacts[0]))
                model.sendTo(listOf(repository.contacts[1]))
                assertEquals(2, repository.sent.size)
                assertEquals(setOf("mom", "dad"), model.sendingContacts.value)
                repository.completions[1](Result.success(Unit))
                assertEquals(setOf("mom"), model.sendingContacts.value)
                model.sendTo(listOf(repository.contacts[2]))
                assertEquals(3, repository.sent.size)
                repository.completions[0](Result.success(Unit))
                repository.completions[0](Result.failure(Exception("duplicate callback")))
                assertEquals(setOf("friend"), model.sendingContacts.value)
                repository.completions[2](Result.success(Unit))
                assertTrue(model.sendingContacts.value!!.isEmpty())
                model.sendTo(listOf(repository.contacts[1]))
                assertEquals(4, repository.sent.size)
            }
        }
    }

    @Test fun failedContactRetainsItsIdAndAttachmentWhileAnotherContactSends() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[MainViewModel::class.java]
                model.voice.value = VoiceState("ready", url = "original")
                model.sendTo(listOf(repository.contacts[0]))
                val firstId = repository.alertIds[0]
                repository.completions[0](Result.failure(AlertProblem(R.string.alert_unconfirmed, true, true)))
                model.voice.value = VoiceState("ready", url = "different")
                model.sendTo(listOf(repository.contacts[1]))
                model.sendTo(listOf(repository.contacts[0]))
                assertEquals(firstId, repository.alertIds[2])
                assertEquals("original", repository.sent[2].second.voiceUrl)
                assertEquals("different", repository.sent[1].second.voiceUrl)
                assertEquals(setOf("mom", "dad"), model.sendingContacts.value)
            }
        }
    }

    @Test fun recreationKeepsPendingSendAndFinishingCancelsIt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].sendTo(listOf(repository.contacts[0])) }
            scenario.recreate()
            scenario.onActivity {
                assertEquals(setOf("mom"), ViewModelProvider(it)[MainViewModel::class.java].sendingContacts.value)
                assertEquals(1, repository.sent.size)
                assertTrue(repository.cancelledIds.isEmpty())
            }
        }
        assertEquals(repository.alertIds.toSet(), repository.cancelledIds)
    }

    @Test fun aRejectedStatusCheckCannotDiscardAnEarlierUnconfirmedAlertId() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[MainViewModel::class.java]
                val contact = listOf(repository.contacts[0])
                model.sendTo(contact)
                repository.completions[0](Result.failure(AlertProblem(R.string.alert_unconfirmed, true, true)))
                model.sendTo(contact)
                repository.completions[1](Result.failure(AlertProblem(R.string.contact_unavailable)))
                model.sendTo(contact)
                assertEquals(1, repository.alertIds.toSet().size)
            }
        }
    }

    @Test fun definiteVoiceRejectionAllowsANewRecordingWithoutReusingRejectedAttachment() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[MainViewModel::class.java]
                val contact = listOf(repository.contacts[0])
                model.voice.value = VoiceState("ready", url = "expired")
                model.sendTo(contact)
                repository.completions[0](Result.failure(AlertProblem(R.string.alert_voice_expired)))
                model.voice.value = VoiceState("ready", url = "fresh")
                model.sendTo(contact)
                assertNotEquals(repository.alertIds[0], repository.alertIds[1])
                assertEquals("fresh", repository.sent[1].second.voiceUrl)
            }
        }
    }

    @Test fun newAlertReplacesVisibleSenderAndMissingActionsAreDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = IncomingAlert("first", "Mom", "", null, null, null).intent(context)
        ActivityScenario.launch<AlertPopupActivity>(first).use { scenario ->
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            onView(withId(R.id.tvSenderName)).check(matches(withText("Mom")))
            onView(withId(R.id.btnCallBack)).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            onView(withId(R.id.alertVoiceCard)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            val voiceUrl = "https://res.cloudinary.com/dqrb7jasx/video/upload/alert-preview.m4a"
            scenario.onActivity { it.startActivity(IncomingAlert("with-attachments", "Mom", "+919876543210", 28.6139, 77.2090, voiceUrl).intent(it)) }
            onView(withId(R.id.btnMap)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.btnStopAndListen)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.btnCallBack)).check(matches(isDisplayed()))
            onView(withId(R.id.btnDismiss)).check(matches(isDisplayed()))
            scenario.onActivity { it.findViewById<androidx.core.widget.NestedScrollView>(R.id.alertDetailsScroll).scrollTo(0, 0) }
            screenshot("alert-with-attachments")
            InstrumentationRegistry.getInstrumentation().runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) }
            onView(withId(R.id.tvSenderName)).check(matches(withText("Mom")))
            screenshot("alert-dark")
            InstrumentationRegistry.getInstrumentation().runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO) }
            scenario.onActivity { it.startActivity(IncomingAlert("second", "Dad", "+919876543210", null, null, null).intent(it)) }
            onView(withId(R.id.tvSenderName)).check(matches(withText("Dad")))
            onView(withId(R.id.tvSenderAvatar)).check(matches(withText("D")))
            onView(withId(R.id.btnCallBack)).check(matches(isEnabled()))
            onView(withId(R.id.btnMap)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withId(R.id.alertVoiceCard)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            screenshot("alert")
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            onView(withId(R.id.tvSenderName)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.btnCallBack)).check(matches(isDisplayed()))
            onView(withId(R.id.btnDismiss)).check(matches(isDisplayed()))
            screenshot("alert-landscape")
            onView(withId(R.id.btnDismiss)).perform(click())
            val finishDeadline = android.os.SystemClock.elapsedRealtime() + 3000
            while (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED && android.os.SystemClock.elapsedRealtime() < finishDeadline) android.os.SystemClock.sleep(50)
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test fun captureProductionLayoutsInLightAndDarkThemes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            onView(withId(R.id.homePage)).check(matches(isDisplayed())); screenshot("home-light")
            onView(withId(R.id.nav_circle)).perform(click()); screenshot("circle-light")
            onView(withId(R.id.nav_settings)).perform(click()); screenshot("settings-light")
            InstrumentationRegistry.getInstrumentation().runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) }
            onView(withId(R.id.nav_home)).perform(click()); screenshot("home-dark")
            onView(withId(R.id.nav_circle)).perform(click()); screenshot("circle-dark")
            onView(withId(R.id.nav_settings)).perform(click()); screenshot("settings-dark")
        }
    }

    @Test fun landscapeKeepsNavigationAndScrolledActionsReachable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            onView(withId(R.id.btnEmergency)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.nav_circle)).perform(click())
            onView(withId(R.id.btnAddContact)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.nav_settings)).perform(click())
            onView(withId(R.id.btnLogout)).perform(scrollTo()).check(matches(isDisplayed()))
            screenshot("settings-landscape")
        }
    }

    @Test fun homeContactActionRequiresConfirmationAndUsesExistingSender() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val action = org.hamcrest.Matchers.allOf(withId(R.id.btnEmergencyItem),
                isDescendantOfA(withId(R.id.recyclerHomeContacts)), withText(context.getString(R.string.send_to, "Mom")))
            onView(action).perform(scrollTo(), click())
            assertTrue(repository.sent.isEmpty())
            onView(withId(android.R.id.button2)).perform(click())
            assertTrue(repository.sent.isEmpty())
            onView(action).perform(scrollTo(), click())
            onView(org.hamcrest.Matchers.allOf(withText(R.string.send_sos), withId(android.R.id.button1))).perform(click())
            assertEquals(listOf("mom"), repository.sent.map { it.first.uid })
        }
    }

    @Test fun historyCardsAndEmptyStateWorkInBothThemesAndSurviveRotation() {
        repository.history = listOf(EmergencyHistory("record", "alert", "mom", "Mom", "+919876543210", 28.6139, 77.2090,
            "https://res.cloudinary.com/dqrb7jasx/raw/upload/v1/sos_voice_memos/example.3gp", 1790390000000, sirenStatus = "timed_out"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.nav_history)).perform(click())
            onView(withId(R.id.historyName)).check(matches(withText("Mom")))
            onView(withId(R.id.historyStatus)).check(matches(withText(R.string.history_timeout)))
            onView(withId(R.id.historyMap)).check(matches(isDisplayed()))
            onView(withId(R.id.historyVoice)).check(matches(isDisplayed()))
            screenshot("history-light")
            scenario.recreate()
            onView(withId(R.id.historyPage)).check(matches(isDisplayed()))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) }
            onView(withId(R.id.historyPage)).check(matches(isDisplayed())); screenshot("history-dark")
            scenario.onActivity { repository.history = emptyList(); ViewModelProvider(it)[MainViewModel::class.java].reload() }
            onView(withId(R.id.historyStateTitle)).check(matches(withText(R.string.history_empty_title)))
            scenario.onActivity { ViewModelProvider(it)[MainViewModel::class.java].historyError.value = true }
            onView(withId(R.id.historyStateTitle)).check(matches(withText(R.string.history_error)))
            onView(withId(R.id.btnRetryHistory)).check(matches(isDisplayed()))
        }
    }

    @Test fun requestNotificationOpensRequestsAndReportRemovesThePendingRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).setAction(RequestNotifications.ACTION)
            .putExtra("type", RequestNotifications.TYPE).putExtra("toUid", "test-user").putExtra("requestId", "request")
        ActivityScenario.launch<MainActivity>(intent).use {
            onView(withId(R.id.btnReport)).perform(click())
            onView(withId(R.id.dialogEditText)).perform(typeText("I do not know this person"), closeSoftKeyboard())
            onView(withId(android.R.id.button1)).perform(click())
            assertEquals(1, repository.reportCalls)
            onView(withId(R.id.tvRequestsStateTitle)).check(matches(withText(R.string.empty_requests_title)))
            pressBack()
        }
    }

    @Test fun requestChannelAndBackgroundTapIntentAreConfigured() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        RequestNotifications.createChannel(context)
        val channel = context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(RequestNotifications.CHANNEL)
        assertEquals(android.app.NotificationManager.IMPORTANCE_HIGH, channel.importance)
        val resolved = Intent(RequestNotifications.ACTION).setPackage(context.packageName).resolveActivity(context.packageManager)
        assertEquals(MainActivity::class.java.name, resolved?.className)
    }

    @Test fun historyShowsSentReceivedAndEveryOutcomeEvenWithoutAudio() {
        val original = EmergencyHistory("event", "alert", "mom", "Mom", "+919876543210", 28.6, 77.2,
            null, System.currentTimeMillis())
        repository.history = listOf(original)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.nav_history)).perform(click())
            // RecyclerView cross-fades retain both holders briefly during rapid test updates.
            scenario.onActivity { it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerHistory).itemAnimator = null }
            val cases = listOf(
                original to R.string.history_saved,
                original.copy(sirenStatus = "stopped") to R.string.history_stopped,
                original.copy(sirenStatus = "timed_out") to R.string.history_timeout,
                original.copy(sirenStatus = "interrupted") to R.string.history_interrupted,
                original.copy(sirenStatus = "received", receivedAt = 1) to R.string.history_received_on_device,
                original.copy(deliveryStatus = "unconfirmed") to R.string.history_unconfirmed,
                original.copy(deliveryStatus = "unavailable") to R.string.history_unavailable,
                original.copy(direction = "sent") to R.string.history_sent_accepted,
                original.copy(direction = "sent", deliveryStatus = "unavailable") to R.string.history_unavailable
            )
            for ((record, status) in cases) {
                scenario.onActivity { repository.history = listOf(record); ViewModelProvider(it)[MainViewModel::class.java].reloadHistory() }
                onView(withId(R.id.historyStatus)).check(matches(withText(status)))
                onView(withId(R.id.historyDirection)).check(matches(withText(if (record.direction == "sent") R.string.history_sent else R.string.history_received)))
                onView(withId(R.id.historyName)).check(matches(withText("Mom")))
            }
            scenario.onActivity { repository.history = listOf(original.copy(voiceExpired = true)); ViewModelProvider(it)[MainViewModel::class.java].reloadHistory() }
            onView(withId(R.id.historyVoiceExpired)).check(matches(isDisplayed()))
            onView(withId(R.id.historyVoice)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withId(R.id.historyMap)).check(matches(isDisplayed()))
        }
    }

    @Test fun allHistoryIsAccessibleInPagesAndLoadedWindowSurvivesRotation() {
        repository.history = (120 downTo 1).map { index ->
            EmergencyHistory("record-$index", "alert-$index", "mom", "Mom", "123", null, null, null,
                index.toLong(), direction = if (index % 2 == 0) "sent" else "received")
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.nav_history)).perform(click())
            scenario.onActivity { assertEquals(50, ViewModelProvider(it)[MainViewModel::class.java].history.value!!.size) }
            onView(withId(R.id.btnMoreHistory)).perform(click())
            scenario.onActivity { assertEquals(100, ViewModelProvider(it)[MainViewModel::class.java].history.value!!.size) }
            scenario.recreate()
            onView(withId(R.id.historyPage)).check(matches(isDisplayed()))
            scenario.onActivity { assertEquals(100, ViewModelProvider(it)[MainViewModel::class.java].history.value!!.size) }
            onView(withId(R.id.btnMoreHistory)).perform(click())
            scenario.onActivity {
                val items = ViewModelProvider(it)[MainViewModel::class.java].history.value!!
                assertEquals(120, items.size); assertEquals("record-1", items.last().id)
            }
            onView(withId(R.id.btnMoreHistory)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Rotation and scroll assertions can finish before the system compositor settles.
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

class FakeRepository : AppRepository {
    override val currentUid = "test-user"
    var contacts = listOf(TrustedContact("mom", "Mom", "+919876543210"), TrustedContact("dad", "Dad", "+919876543211"))
    var requests = listOf(RequestItem("request", "friend", "Ananya", "+919876543212", currentUid, "+919876543213"))
    private var requestListener: ((Result<List<RequestItem>>) -> Unit)? = null
    val sent = mutableListOf<Pair<TrustedContact, AlertAttachment>>()
    val alertIds = mutableListOf<String>()
    val completions = mutableListOf<(Result<Unit>) -> Unit>()
    var addCalls = 0
    var acceptCalls = 0
    var reportCalls = 0
    override fun observeProfile(callback: (Result<UserProfile>) -> Unit) = Subscription {}.also { callback(Result.success(UserProfile(currentUid, "Aarav", "+919876543213", "aarav@example.com"))) }
    override fun observeHistory(limit: Int, callback: (Result<HistoryPage>) -> Unit) = Subscription {}.also {
        callback(Result.success(HistoryPage(history.take(limit), history.size > limit)))
    }
    var history = emptyList<EmergencyHistory>()
    override fun observeContacts(callback: (Result<List<TrustedContact>>) -> Unit) = Subscription {}.also { callback(Result.success(contacts)) }
    override fun observeRequests(callback: (Result<List<RequestItem>>) -> Unit): Subscription {
        requestListener = callback; callback(Result.success(requests)); return Subscription { requestListener = null }
    }
    override fun addContact(phone: String, callback: (Result<Unit>) -> Unit) { addCalls++; callback(Result.success(Unit)) }
    override fun updatePhone(phone: String, callback: (Result<Unit>) -> Unit) { callback(Result.success(Unit)) }
    override fun setBlocked(contact: TrustedContact, blocked: Boolean, callback: (Result<Unit>) -> Unit) { callback(Result.success(Unit)) }
    override fun removeContact(contact: TrustedContact, callback: (Result<Unit>) -> Unit) { callback(Result.success(Unit)) }
    override fun acceptRequest(request: RequestItem, callback: (Result<Unit>) -> Unit) {
        acceptCalls++; requests = emptyList(); requestListener?.invoke(Result.success(requests)); callback(Result.success(Unit))
    }
    override fun rejectRequest(request: RequestItem, callback: (Result<Unit>) -> Unit) { callback(Result.success(Unit)) }
    override fun reportRequest(request: RequestItem, reason: String, callback: (Result<Unit>) -> Unit) { reportCalls++; requests = emptyList(); requestListener?.invoke(Result.success(requests)); callback(Result.success(Unit)) }
    override fun sendAlert(contact: TrustedContact, attachment: AlertAttachment, alertId: String, callback: (Result<Unit>) -> Unit): Subscription {
        sent += contact to attachment; alertIds += alertId; completions += callback
        return Subscription { cancelledIds += alertId }
    }
    val cancelledIds = mutableSetOf<String>()
    override fun syncToken() { }
}
