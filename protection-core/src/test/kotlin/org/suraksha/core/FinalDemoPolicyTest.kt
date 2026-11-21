package org.suraksha.core

import org.junit.Assert.*
import org.junit.Test

/** These inputs are parsed by the ordinary engine; this suite never visits any destination. */
class FinalDemoPolicyTest {
    companion object {
        private fun resource(name: String) = requireNotNull(
            FinalDemoPolicyTest::class.java.classLoader.getResourceAsStream(name)
        ).bufferedReader().use { it.readText() }
        private val engine by lazy { VerdictEngine.load(::resource) }
    }

    @Test fun normalExamplesRemainQuietWithoutAnyVerdictOverride() {
        listOf(DemoExamples.NORMAL_URL, DemoExamples.AIRTEL_URL, DemoExamples.GOOD_MONK_URL).forEach { url ->
            val result = engine.evaluate(url, url)
            assertEquals(url, Level.SAFE, result.level)
            assertTrue(result.reasonCodes.isEmpty())
        }
    }

    @Test fun reservedExamplesExerciseThreeRealRuleOutcomes() {
        val ordinary = engine.evaluate("https://photos.example/album", "")
        val caution = engine.evaluate(DemoExamples.SUSPICIOUS_URL, DemoExamples.SUSPICIOUS_URL)
        val danger = engine.evaluate(DemoExamples.HIGH_RISK_URL, DemoExamples.HIGH_RISK_MESSAGE)
        assertEquals(Level.SAFE, ordinary.level)
        assertEquals(Level.SUSPICIOUS, caution.level)
        assertEquals(Level.DANGEROUS, danger.level)
        listOf(ordinary, caution, danger).forEach {
            assertTrue(ReasonCode.TEST_INPUT in it.reasonCodes)
            assertFalse(ReasonCode.DEMO_ONLY in it.reasonCodes)
            assertFalse(ReasonCode.GOOGLE_REPUTATION in it.reasonCodes)
        }
        assertTrue(ReasonCode.PHISHING_KEYWORDS in caution.reasonCodes)
        assertTrue(ReasonCode.LOCAL_RISK in danger.reasonCodes)
        assertTrue(ReasonCode.BRAND_IN_DOMAIN in danger.reasonCodes)
    }

    @Test fun metadataMarkerCannotChangeAnExistingVerdict() {
        for (level in Level.entries) {
            val existing = Verdict(level, 0.42, setOf(ReasonCode.SCAM_LANGUAGE))
            val marked = ExampleLinks.markTestInput("https://arbitrary.example/path", existing)
            assertEquals(existing.level, marked.level)
            assertEquals(existing.score, marked.score, 0.0)
            assertEquals(existing.reasonCodes + ReasonCode.TEST_INPUT, marked.reasonCodes)
            assertEquals(existing, ExampleLinks.markTestInput(DemoExamples.NORMAL_URL, existing))
        }
    }

    @Test fun reservedHostDetectionUsesTheActualHostAndSupportsBareLinks() {
        assertTrue(ExampleLinks.isReservedExample("account-support.example/login"))
        assertTrue(ExampleLinks.isReservedExample("https://A.B.EXAMPLE./login"))
        assertFalse(ExampleLinks.isReservedExample("https://fixture.example@wikipedia.org/"))
        assertFalse(ExampleLinks.isReservedExample("https://www.wikipedia.org/?next=fixture.example"))
        assertFalse(ExampleLinks.isReservedExample("https://www.wikipedia.org/fixture.example"))
        assertFalse(ExampleLinks.isReservedExample("https://example.org/"))
    }

    @Test fun manualAndNotificationSourcesShareClassificationAndTestMetadata() {
        val pipeline = MessagePipeline(engine)
        val manual = pipeline.scan(DemoExamples.HIGH_RISK_MESSAGE, "manual", false).single()
        val incoming = pipeline.scan(DemoExamples.HIGH_RISK_MESSAGE, "com.whatsapp", false).single()
        assertEquals(manual.domain, incoming.domain)
        assertEquals(manual.verdict, incoming.verdict)
        assertTrue(ReasonCode.TEST_INPUT in incoming.verdict.reasonCodes)
    }

    @Test fun executableExtensionAloneNeverMeansTheDownloadIsMalicious() {
        listOf("https://downloads.example/app.apk", "https://files.example/MyApp.XAPK",
            "https://packages.example/tool.apks", "https://files.example/desktop.exe").forEach { url ->
            val result = engine.evaluate(url, "Here is the official release file: $url")
            assertNotEquals(url, Level.DANGEROUS, result.level)
            assertFalse(url, ReasonCode.LOCAL_RISK in result.reasonCodes)
        }
    }

    @Test fun multipleWeakStructuralCluesStillStayQuiet() {
        listOf(
            "https://x8k2-v9d3-m4p7-n6q2.photos.example:8443/login?next=" + "a".repeat(220),
            "https://xn--bcher-kva.storage.example:8443/account/login?next=" + "a".repeat(220)
        ).forEach { url ->
            val result = engine.evaluate(url, "Here are the pictures you requested")
            assertEquals(url, Level.SAFE, result.level)
            assertEquals(setOf(ReasonCode.TEST_INPUT), result.reasonCodes)
        }
    }

    @Test fun ordinaryUrgentNoticesAndOtpSafetyMessagesStayQuiet() {
        listOf(
            "Your subscription expires today. Renew in the official app. Never share your OTP.",
            "Scheduled maintenance tonight: service will be disconnected for ten minutes. No payment is required.",
            "123456 is your OTP for login. Do not share it with anyone.",
            "Read the training article explaining password security."
        ).forEach { message -> assertEquals(message, Level.SAFE, engine.evaluateText(message).level) }
    }

    @Test fun quotedScamAwarenessIsNotTreatedAsAnInstructionToTheRecipient() {
        val quoted = "Security awareness: scammers say \"Your account will be blocked. Share your OTP immediately to verify your account\". This is an example, not a request."
        assertEquals(Level.SAFE, engine.evaluateText(quoted).level)
        val withLink = "Phishing example: \"${DemoExamples.HIGH_RISK_MESSAGE}\". Discuss this example in training."
        assertNotEquals(Level.DANGEROUS, engine.evaluate(DemoExamples.HIGH_RISK_URL, withLink).level)
    }

    @Test fun urgentAccountNoticeOnAnUnverifiedDestinationIsAtMostACaution() {
        val url = "https://customer-notice.example/details"
        val message = "SBI reminder: KYC expires today. See account instructions $url"
        assertNotEquals(Level.DANGEROUS, engine.evaluate(url, message).level)
    }

    @Test fun directCredentialPressureStillProducesAnExplainedUnconfirmedWarning() {
        val result = engine.evaluateText("Your account will be blocked. Share your OTP immediately to verify your account")
        assertEquals(Level.DANGEROUS, result.level)
        assertTrue(ReasonCode.CREDENTIAL_REQUEST in result.reasonCodes)
        assertTrue(ReasonCode.LOCAL_RISK in result.reasonCodes)
        assertFalse(ReasonCode.GOOGLE_REPUTATION in result.reasonCodes)
        assertTrue(ReasonMessages.get(ReasonCode.LOCAL_RISK, "en").contains("unconfirmed"))
    }

    @Test fun noMatchDoesNotPromiseSafetyOrEraseTheTestLabel() {
        val local = engine.evaluate(DemoExamples.HIGH_RISK_URL, DemoExamples.HIGH_RISK_MESSAGE)
        listOf(ReputationPolicy.noMatch(local), ReputationPolicy.notChecked(local)).forEach {
            assertEquals(Level.DANGEROUS, it.level)
            assertTrue(ReasonCode.TEST_INPUT in it.reasonCodes)
            assertTrue(ReasonCode.LOCAL_RISK in it.reasonCodes)
            assertFalse(ReasonCode.GOOGLE_REPUTATION in it.reasonCodes)
        }
        listOf("en", "hi", "pa").forEach {
            assertTrue(ReasonMessages.get(ReasonCode.TEST_INPUT, it).isNotBlank())
        }
    }
}
