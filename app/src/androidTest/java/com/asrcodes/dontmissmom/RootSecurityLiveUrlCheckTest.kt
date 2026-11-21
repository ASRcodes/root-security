package com.asrcodes.dontmissmom

import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.suraksha.core.Level
import org.suraksha.core.ReasonCode
import org.suraksha.core.ReasonMessages
import org.suraksha.core.ScanResult
import org.suraksha.kavach.AlertActivity
import org.suraksha.kavach.LoadState
import org.suraksha.kavach.data.AppSettings
import org.suraksha.kavach.R as ProtectionR

/**
 * Human-authorized live check only: three manual scans through the real app/provider.
 * Never reads credentials, resets storage, changes preferences, sends SMS/SOS, visits
 * destinations, captures screens, or interacts with API-key fields. Only three normal
 * metadata history entries are produced by the application's manual-scan path.
 */
@RunWith(AndroidJUnit4::class)
class RootSecurityLiveUrlCheckTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)

    @Test fun approvedLinksUseTheSavedLiveProviderAndShowTheGoogleTestWarning() {
        assumeTrue("Live checks require the explicit live runner", instrumentation is RootSecurityLiveRunner)
        val application = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("Live checks require the production application", DmmApplication::class.java.name, application.javaClass.name)
        val app = application as DmmApplication
        val cases = listOf(
            "airtel" to "i.airtel.in/rc349sil3",
            "goodmonk" to "https://weurl.co/GDMNKS/oo8a70",
            "google-harmless-test" to "https://testsafebrowsing.appspot.com/s/phishing.html"
        )
        val results = runBlocking {
            try {
                withTimeout(60_000) {
                    assertEquals(LoadState.READY, app.modelState.first { it != LoadState.LOADING })
                    app.online.state.first { it.keyConfigured && it.enabled }
                }
            } catch (_: TimeoutCancellationException) {
                report(app, "configuration", null)
                throw AssertionError("Saved online checking did not become enabled; see sanitized provider status")
            }
            cases.map { (label, input) ->
                val outcome = withTimeout(20_000) {
                    // simulate=true means an explicit manual check, which skips guardian delivery.
                    app.processMessage(input, "approved-live-validation", simulate = true)
                }
                val result = outcome.results.singleOrNull()
                report(app, label, result)
                label to result
            }
        }.toMap()

        for (label in listOf("airtel", "goodmonk")) {
            val result = requireNotNull(results[label]) { "$label did not return one result; see provider status" }
            assertEquals("$label must not be flagged; no override is applied", Level.SAFE, result.verdict.level)
            assertTrue("$label needs a completed live no-match response", ReasonCode.REPUTATION_NO_MATCH in result.verdict.reasonCodes)
            assertFalse(ReasonCode.REPUTATION_NOT_CHECKED in result.verdict.reasonCodes)
            assertFalse(ReasonCode.DEMO_ONLY in result.verdict.reasonCodes)
        }
        val test = requireNotNull(results["google-harmless-test"]) { "Google test did not return a result; see provider status" }
        assertEquals("Google's test fixture requires an actual provider match", Level.DANGEROUS, test.verdict.level)
        assertTrue(ReasonCode.GOOGLE_REPUTATION in test.verdict.reasonCodes)
        assertTrue(ReasonCode.PROVIDER_TEST in test.verdict.reasonCodes)
        assertFalse(ReasonCode.DEMO_ONLY in test.verdict.reasonCodes)

        // Open the genuine result without replaying audio or triggering any family action.
        ActivityScenario.launch<AlertActivity>(AlertActivity.intent(app, test, speak = false)).use {
            val localized = AppSettings.localized(app)
            assertTrue("The real test warning must render", device.wait(Until.hasObject(
                By.pkg(app.packageName).text(localized.getString(ProtectionR.string.sk_google_test_title))), 15_000))
            assertTrue(device.hasObject(By.pkg(app.packageName).text(localized.getString(ProtectionR.string.sk_google_test_badge))))
            assertFalse(device.hasObject(By.pkg(app.packageName).text(localized.getString(ProtectionR.string.sk_demo_badge))))
            val attribution = ReasonMessages.get(ReasonCode.GOOGLE_REPUTATION, app.settings.state.value.language)
            repeat(5) {
                if (!device.hasObject(By.pkg(app.packageName).text(attribution))) {
                    assertEquals("Keep live UI verification within the app", app.packageName, device.currentPackageName)
                    val x = device.displayWidth * 4 / 5
                    device.swipe(x, device.displayHeight * 3 / 4, x, device.displayHeight / 3, 25)
                    device.waitForIdle(1_000)
                }
            }
            assertTrue("Google attribution must be visible", device.hasObject(By.pkg(app.packageName).text(attribution)))
            SystemClock.sleep(2_000)
        }
    }

    /** Intentionally excludes input URLs, request details, response bodies and credentials. */
    private fun report(app: DmmApplication, label: String, result: ScanResult?) {
        val summary = JSONObject()
            .put("case", label)
            .put("domain", result?.domain.orEmpty())
            .put("level", result?.verdict?.level?.name ?: "UNAVAILABLE")
            .put("reasonCodes", JSONArray(result?.verdict?.reasonCodes?.map { it.name }?.sorted().orEmpty()))
            .put("providerLastStatus", app.online.state.value.lastStatus)
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "ROOT_SECURITY_LIVE $summary\n") })
    }
}
