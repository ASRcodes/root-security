package com.asrcodes.dontmissmom

import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.suraksha.core.*
import org.suraksha.kavach.LoadState
import java.io.IOException
import java.util.Base64
import org.suraksha.kavach.MainActivity as ProtectionActivity
import org.suraksha.kavach.R as ProtectionR

/**
 * Emulator-only: resets protection history/settings and uses an encrypted fixture key in
 * DmmTestApplication's isolated preferences. The transport never makes an external request.
 * Select RootSecurityLinkRegressionTest instead when testing a user's physical phone.
 */
@RunWith(AndroidJUnit4::class)
class RootSecurityOnlineIntegrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<DmmTestApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val ui by lazy { RootSecurityUiHarness(app) }
    private var isolatedRunStarted = false
    private val urls by lazy {
        SafeBrowsingUrls(PublicSuffixList(app.assets.open("public_suffix_list.dat").bufferedReader().use { it.readText() }))
    }

    @Before fun setup() = runBlocking {
        assumeTrue("This suite resets state and must only run on an emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        isolatedRunStarted = true
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
        app.reputationResponder = { noMatchResponse() }
        assertTrue(app.online.configure(DmmTestApplication.REPUTATION_FIXTURE_KEY))
    }

    @After fun cleanup() = runBlocking {
        if (isolatedRunStarted) {
            app.reputationResponder = { throw IOException("No reputation test response configured") }
            app.dismissWarnings()
            assertTrue(app.clearProtectionData())
        }
    }

    @Suppress("DEPRECATION")
    @Test fun bothReportedLinksReceiveNoMatchWithoutAnyGlobalDemoOverride() = runBlocking {
        app.setDemo(true) // Compatibility method must stay a no-op in the real app pipeline.
        assertFalse(app.demoEnabled.value)
        val marker = "message_body_fixture_not_to_store_81927"
        val result = app.processMessage("$marker i.airtel.in/rc349sil3 https://weurl.co/GDMNKS/oo8a70", "online-regression", simulate = true)
        assertTrue(result.available)
        assertEquals(2, result.results.size)
        result.results.forEach {
            assertEquals(Level.SAFE, it.verdict.level)
            assertEquals(setOf(ReasonCode.REPUTATION_NO_MATCH), it.verdict.reasonCodes)
        }
        assertTrue(app.reputationRequests.isNotEmpty())
        app.reputationRequests.flatten().forEach { prefix -> assertEquals(4, Base64.getDecoder().decode(prefix).size) }
        withTimeout(15_000) { app.history.first { it.size >= 2 } }
        assertFalse(app.history.value.toString().contains(marker))
        assertFalse(app.history.value.toString().contains("rc349sil3"))
        assertFalse(app.history.value.toString().contains(DmmTestApplication.REPUTATION_FIXTURE_KEY))
        assertTrue(app.sms.isEmpty())
    }

    @Test fun exactProviderHashProducesAnAttributedHarmlessTestVerdict() = runBlocking {
        val hash = urls.fullHashes(GOOGLE_TEST_URL).first()
        app.reputationResponder = { prefixes ->
            assertTrue(prefixOf(hash) in prefixes)
            threatResponse(hash)
        }
        assertTrue(app.guardian.configure("+12025550142", enabled = true))
        val outcome = app.processMessage(GOOGLE_TEST_URL, "manual-provider-fixture", simulate = true)
        assertTrue(outcome.available)
        val result = outcome.results.single()
        assertEquals(Level.DANGEROUS, result.verdict.level)
        assertEquals(setOf(ReasonCode.GOOGLE_REPUTATION, ReasonCode.PROVIDER_TEST), result.verdict.reasonCodes)
        assertTrue(result.timestamp <= System.currentTimeMillis())
        assertTrue(System.currentTimeMillis() - result.timestamp < 30_000)
        assertTrue(app.sms.isEmpty())
        assertFalse(ReasonCode.DEMO_ONLY in result.verdict.reasonCodes)
    }

    @Test fun matchingOnlyAFourBytePrefixCannotFlagAnOrdinaryLink() = runBlocking {
        val input = "https://weurl.co/GDMNKS/oo8a70"
        val hashes = urls.fullHashes(input)
        val original = hashes.first()
        val bytes = Base64.getDecoder().decode(original)
        bytes[31] = (bytes[31].toInt() xor 1).toByte()
        val collision = Base64.getEncoder().encodeToString(bytes)
        assertEquals(prefixOf(original), prefixOf(collision))
        assertFalse(collision in hashes)
        app.reputationResponder = { prefixes ->
            assertTrue(prefixOf(collision) in prefixes)
            threatResponse(collision)
        }
        val result = app.processMessage(input, "prefix-collision-fixture", simulate = true).results.single()
        assertEquals(Level.SAFE, result.verdict.level)
        assertEquals(setOf(ReasonCode.REPUTATION_NO_MATCH), result.verdict.reasonCodes)
        assertTrue(app.sms.isEmpty())
    }

    @Test fun transportFailureIsNotCheckedAndNeverAnAlarm() = runBlocking {
        app.reputationResponder = { throw IOException("HTTP 503 test fixture") }
        val result = app.processMessage("i.airtel.in/rc349sil3", "transport-failure-fixture", simulate = true).results.single()
        assertEquals(Level.SAFE, result.verdict.level)
        assertEquals(setOf(ReasonCode.REPUTATION_NOT_CHECKED), result.verdict.reasonCodes)
        assertFalse(app.online.state.value.lastStatus.contains(DmmTestApplication.REPUTATION_FIXTURE_KEY))
        assertTrue(app.sms.isEmpty())
    }

    @Test fun malformedProviderReplyIsNotACleanReputationResult() = runBlocking {
        app.reputationResponder = { "{\"cacheDuration\":false,\"fullHashes\":[]}" }
        val result = app.processMessage("https://weurl.co/GDMNKS/oo8a70", "malformed-response-fixture", simulate = true).results.single()
        assertEquals(Level.SAFE, result.verdict.level)
        assertEquals(setOf(ReasonCode.REPUTATION_NOT_CHECKED), result.verdict.reasonCodes)
    }

    @Test fun fixtureCredentialIsEncryptedAndNeverShownInConfigurationState() {
        val saved = app.reputationTestContext.getSharedPreferences("kavach_sealed_secrets", 0).all
        assertTrue(saved.containsKey("safe_browsing_api_key"))
        assertFalse(saved.toString().contains(DmmTestApplication.REPUTATION_FIXTURE_KEY))
        assertFalse(app.online.state.value.toString().contains(DmmTestApplication.REPUTATION_FIXTURE_KEY))
        assertTrue(app.online.state.value.enabled)
        assertTrue(app.online.state.value.keyConfigured)
    }

    @Test fun manualGoogleFixtureOpensAnHonestlyLabeledWarningThroughTheRealUi() {
        val hash = urls.fullHashes(GOOGLE_TEST_URL).first()
        app.reputationResponder = { threatResponse(hash) }
        ActivityScenario.launch<ProtectionActivity>(Intent(app, ProtectionActivity::class.java).putExtra("page", "DEBUG")).use {
            val demoTitle = app.getString(ProtectionR.string.sk_demo_lab)
            assertEquals(demoTitle, ui.findText(demoTitle, towardBottom = false).text)
            scrollToText(ProtectionR.string.sk_demo_google_test).click()
            scrollToText(ProtectionR.string.sk_check_message, towardBottom = false).click()
            assertTrue(device.wait(Until.hasObject(By.text(app.getString(ProtectionR.string.sk_google_test_title))), 15_000))
            assertTrue(device.hasObject(By.text(app.getString(ProtectionR.string.sk_google_test_badge))))
            assertFalse(device.hasObject(By.text(app.getString(ProtectionR.string.sk_demo_badge))))
            assertTrue(app.reputationRequests.isNotEmpty())
            assertTrue(app.sms.isEmpty())
            device.findObject(By.text(app.getString(ProtectionR.string.sk_understand))).click()
        }
    }

    @Test fun explicitAlertPreviewIsLabeledAndDoesNotCheckLinksOrWriteHistory() {
        ActivityScenario.launch<ProtectionActivity>(Intent(app, ProtectionActivity::class.java).putExtra("page", "DEBUG")).use {
            val demoTitle = app.getString(ProtectionR.string.sk_demo_lab)
            assertEquals(demoTitle, ui.findText(demoTitle, towardBottom = false).text)
            scrollToText(ProtectionR.string.sk_demo_preview_button).click()
            assertTrue(device.wait(Until.hasObject(By.text(app.getString(ProtectionR.string.sk_simulation_title))), 15_000))
            assertTrue(device.hasObject(By.text(app.getString(ProtectionR.string.sk_demo_badge))))
            assertFalse(device.hasObject(By.text(app.getString(ProtectionR.string.sk_google_test_badge))))
            assertTrue(app.reputationRequests.isEmpty())
            assertTrue(app.history.value.isEmpty())
            assertTrue(app.sms.isEmpty())
            assertFalse(app.demoEnabled.value)
            device.findObject(By.text(app.getString(ProtectionR.string.sk_understand))).click()
        }
    }

    private fun scrollToText(resource: Int, towardBottom: Boolean = true) = ui.findText(app.getString(resource), towardBottom)

    private fun noMatchResponse() = "{\"cacheDuration\":\"300s\",\"fullHashes\":[]}"
    private fun prefixOf(hash: String) = Base64.getEncoder().encodeToString(Base64.getDecoder().decode(hash).copyOf(4))
    private fun threatResponse(hash: String) = JSONObject().put("cacheDuration", "300s")
        .put("fullHashes", JSONArray().put(JSONObject().put("fullHash", hash)
            .put("fullHashDetails", JSONArray().put(JSONObject().put("threatType", "SOCIAL_ENGINEERING")))))
        .toString()

    companion object {
        private const val GOOGLE_TEST_URL = "https://testsafebrowsing.appspot.com/s/phishing.html"
    }
}
