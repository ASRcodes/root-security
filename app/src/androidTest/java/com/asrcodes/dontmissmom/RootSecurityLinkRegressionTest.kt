package com.asrcodes.dontmissmom

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.suraksha.core.*
import org.suraksha.kavach.data.AppSettings
import org.suraksha.kavach.MainActivity as ProtectionActivity
import org.suraksha.kavach.R as ProtectionR

/**
 * Targeted phone-safe suite. It uses deployed assets in memory and only fills UI inputs.
 * No resets, preference changes, processMessage/history writes, network calls, permission
 * grants, guardian actions or SOS actions are performed. DmmTestApplication also fakes SMS.
 */
@RunWith(AndroidJUnit4::class)
class RootSecurityLinkRegressionTest {
    private val app get() = ApplicationProvider.getApplicationContext<DmmTestApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val ui by lazy { RootSecurityUiHarness(app) }
    private fun asset(name: String) = app.assets.open(name).bufferedReader().use { it.readText() }
    private val engine by lazy { VerdictEngine.load(::asset) }

    @Test fun exactReportedLinksStayQuietWithDeployedAssets() {
        val smsBefore = app.sms.size
        val pipeline = MessagePipeline(engine)
        listOf(DemoExamples.NORMAL_URL, DemoExamples.AIRTEL_URL, DemoExamples.GOOD_MONK_URL).forEach { input ->
            val result = pipeline.scan(input, "phone-local-regression", false).single()
            assertEquals(input, Level.SAFE, result.verdict.level)
            assertTrue(input, result.verdict.reasonCodes.isEmpty())
        }
        assertEquals("Read-only checks must not attempt guardian SMS", smsBefore, app.sms.size)
    }

    @Test fun slashAndDeepPathVariantsDoNotCreateThreatsOnAndroid() {
        listOf("www.airtel.in", "www.goodmonk.in", "www.flipkart.com", "store.example").forEach { host ->
            listOf("", "/", "/products/123456", "/account/login", "/?utm_source=whatsapp&ref=1234").forEach { path ->
                val input = "https://$host$path"
                val result = engine.evaluate(input, input)
                assertEquals(input, Level.SAFE, result.level)
                assertTrue(input, (result.reasonCodes - ReasonCode.TEST_INPUT).isEmpty())
            }
        }
    }

    @Test fun highRawUrlScoresRemainDiagnosticWhileStrongLocalCluesAreExplained() {
        val input = "https://docs.python.org/3/library/urllib.parse.html"
        val features = UrlFeatures(PublicSuffixList(asset("public_suffix_list.dat")))
        val score = UrlModel(asset("url_model.json")).score(features.extract(UrlParser.parse(input)!!))
        assertTrue("Fixture must reproduce the retired model failure", score > 0.9)
        assertEquals(Level.SAFE, engine.evaluate(input, "").level)

        val pressure = "Your account will be blocked. Share your OTP immediately to verify your account"
        val text = engine.evaluateText(pressure)
        assertEquals(Level.DANGEROUS, text.level)
        assertTrue(ReasonCode.CREDENTIAL_REQUEST in text.reasonCodes)
        val spoof = engine.evaluate("https://paypal.com.verify-account.example/login", "")
        assertEquals(Level.DANGEROUS, spoof.level)
        assertTrue(ReasonCode.BRAND_IN_SUBDOMAIN in spoof.reasonCodes && ReasonCode.LOCAL_RISK in spoof.reasonCodes)
        assertEquals(Level.SAFE, engine.evaluate("https://weurl.co/GDMNKS/oo8a70", "Please click this link").level)
    }

    @Test fun reservedExamplesExerciseLocalRulesWithoutProviderOrSimulationClaims() {
        val caution = engine.evaluate(DemoExamples.SUSPICIOUS_URL, DemoExamples.SUSPICIOUS_URL)
        val danger = engine.evaluate(DemoExamples.HIGH_RISK_URL, DemoExamples.HIGH_RISK_MESSAGE)
        assertEquals(Level.SUSPICIOUS, caution.level)
        assertEquals(Level.DANGEROUS, danger.level)
        listOf(caution, danger).forEach {
            assertTrue(ReasonCode.TEST_INPUT in it.reasonCodes)
            assertFalse(ReasonCode.DEMO_ONLY in it.reasonCodes)
            assertFalse(ReasonCode.GOOGLE_REPUTATION in it.reasonCodes)
        }
        assertTrue(ReasonCode.LOCAL_RISK in danger.reasonCodes)
        // Merely being an example address must never force a dangerous verdict.
        assertEquals(Level.SAFE, engine.evaluate("https://ordinary.example/", "").level)
    }

    @Suppress("DEPRECATION")
    @Test fun legacyDemoToggleCannotTurnOrdinaryLinksIntoFakeThreats() {
        // The compatibility method is deliberately a no-op in the hardened app.
        // No input sample or global flag may substitute an all-links-dangerous scorer.
        try {
            app.setDemo(true)
            assertFalse("Global fake verdicts must remain unavailable", app.demoEnabled.value)
            val result = MessagePipeline(engine).scan("https://weurl.co/GDMNKS/oo8a70", "demo-isolation", false).single()
            assertEquals(Level.SAFE, result.verdict.level)
            assertFalse(ReasonCode.DEMO_ONLY in result.verdict.reasonCodes)
        } finally {
            app.setDemo(false)
        }
    }

    @Test fun demoSamplesOnlyFillTheEditorWithoutScanningOrOpeningAnAlert() {
        val preferencesBefore = app.settings.state.value
        val smsBefore = app.sms.size
        val monitor = instrumentation.addMonitor("org.suraksha.kavach.AlertActivity", null, false)
        try {
            ActivityScenario.launch<ProtectionActivity>(Intent(app, ProtectionActivity::class.java).putExtra("page", "DEBUG")).use {
                val localized = AppSettings.localized(app)
                val demoTitle = localized.getString(ProtectionR.string.sk_demo_lab)
                assertEquals(demoTitle, ui.findText(demoTitle, towardBottom = false).text)
                listOf(
                    ProtectionR.string.sk_demo_normal_label to DemoExamples.NORMAL_URL,
                    ProtectionR.string.sk_demo_airtel to DemoExamples.AIRTEL_URL,
                    ProtectionR.string.sk_demo_goodmonk to DemoExamples.GOOD_MONK_URL,
                    ProtectionR.string.sk_demo_caution_label to DemoExamples.SUSPICIOUS_URL,
                    ProtectionR.string.sk_demo_danger_label to DemoExamples.HIGH_RISK_MESSAGE,
                    ProtectionR.string.sk_demo_google_test to DemoExamples.GOOGLE_TEST_URL
                ).forEach { (label, input) ->
                    // The example buttons are above the editor in the current Demo lab layout.
                    ui.clickText(localized.getString(label), towardBottom = false)
                    val editor = ui.findEditableText(input, towardBottom = true)
                    assertTrue("The selected sample must remain editable", editor.isEnabled)
                    assertTrue("The actual editor must contain the selected sample", editor.text?.contains(input) == true)
                }
                instrumentation.waitForIdleSync()
                assertEquals("Selecting samples must not trigger a full-screen warning", 0, monitor.hits)
                assertEquals(preferencesBefore, app.settings.state.value)
                assertEquals(smsBefore, app.sms.size)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
