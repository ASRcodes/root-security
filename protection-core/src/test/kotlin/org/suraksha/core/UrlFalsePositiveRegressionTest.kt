package org.suraksha.core

import org.junit.Assert.*
import org.junit.Test

/** Deployment-like URL strings are checked locally; these tests never fetch a URL. */
class UrlFalsePositiveRegressionTest {
    companion object {
        private fun resource(name: String) = requireNotNull(
            UrlFalsePositiveRegressionTest::class.java.classLoader.getResourceAsStream(name)
        ) { "Required exported asset missing: $name" }.bufferedReader().use { it.readText() }

        private val engine by lazy { VerdictEngine.load(::resource) }
        private val suffixes by lazy { PublicSuffixList(resource("public_suffix_list.dat")) }
        private val features by lazy { UrlFeatures(suffixes) }
        private val urlModel by lazy { UrlModel(resource("url_model.json")) }
        private const val pressure = "Your account will be blocked. Share your OTP immediately to verify your account"

        private val ordinaryLinks = listOf(
            "i.airtel.in/rc349sil3",
            "https://weurl.co/GDMNKS/oo8a70",
            "https://www.airtel.in/",
            "https://www.airtel.in/prepaid-recharge/",
            "https://www.airtel.in/login",
            "https://www.goodmonk.in/collections/all",
            "https://www.goodmonk.in/account/login",
            "https://www.goodmonk.in/?utm_source=whatsapp&utm_medium=share&utm_campaign=family_nutrition",
            "https://www.flipkart.com/",
            "https://www.jio.com/",
            "https://www.zomato.com/",
            "https://www.swiggy.com/",
            "https://www.myntra.com/",
            "https://developer.android.com/studio/run/device",
            "https://docs.python.org/3/library/urllib.parse.html",
            "https://scikit-learn.org/stable/",
            "https://en.wikipedia.org/wiki/Android_(operating_system)",
            "https://www.google.com/search?q=weather"
        )
    }

    @Test fun reportedLinksAndOrdinaryDeepLinksHaveNoLocalWarning() {
        ordinaryLinks.forEach { input ->
            val result = engine.evaluate(input, input)
            assertEquals("False positive for $input", Level.SAFE, result.level)
            assertTrue("No unsupported warning reasons for $input", result.reasonCodes.isEmpty())
        }
    }

    @Test fun addingASlashOrChangingOrdinaryPathsDoesNotCreateAThreat() {
        listOf("www.airtel.in", "www.goodmonk.in", "www.flipkart.com", "store.example").forEach { host ->
            listOf("", "/", "/products/123456", "/account/login", "/?utm_source=whatsapp&ref=1234").forEach { path ->
                listOf("https", "http").forEach { scheme ->
                    val input = "$scheme://$host$path"
                    assertEquals(input, Level.SAFE, engine.evaluate(input, input).level)
                }
            }
        }
    }

    @Test fun ordinaryStructuralPropertiesDoNotAddUpToFraudEvidence() {
        listOf(
            "https://bit.ly/family-meetup",
            "https://tinyurl.com/family-meetup",
            "http://192.168.1.1/login",
            "https://[2001:db8::1]/account/login",
            "https://xn--bcher-kva.example/",
            "https://bücher.example/",
            "https://my-family-shared-photo-album.example/login",
            "https://ordinary.example/login?next=" + "a".repeat(180),
            "https://ordinary.xyz/account/login"
        ).forEach { input ->
            val result = engine.evaluate(input, "")
            assertEquals(input, Level.SAFE, result.level)
            assertTrue(input, (result.reasonCodes - ReasonCode.TEST_INPUT).isEmpty())
        }
    }

    @Test fun biasedUrlForestRemainsDiagnosticAndCannotRaiseAnAlert() {
        val input = "https://docs.python.org/3/library/urllib.parse.html"
        assertTrue("Fixture must expose the original failure", urlModel.score(features.extract(UrlParser.parse(input)!!)) >= 0.8)
        val result = engine.evaluate(input, "")
        assertEquals(Level.SAFE, result.level)
        assertFalse(result.reasonCodes.contains(ReasonCode.URL_MODEL))
    }

    @Test fun staleDomainListsCorroborateButCannotAlarmAlone() {
        val local = VerdictEngine(Allowlist("google.com", suffixes), Blocklist("ordinary.example"),
            features, urlModel, TextModel(resource("text_model.json")))
        val previouslyListed = local.evaluate("https://ordinary.example/products/1", "")
        assertEquals(Level.SUSPICIOUS, previouslyListed.level)
        assertEquals(setOf(ReasonCode.KNOWN_PHISHING, ReasonCode.TEST_INPUT), previouslyListed.reasonCodes)
        assertEquals(Level.SAFE, local.evaluate("https://google.com/", "").level)
        // A familiar destination is capped at a caution, whatever the wording says.
        assertEquals(Level.SUSPICIOUS, local.evaluate("https://google.com/", pressure).level)
    }

    @Test fun concreteDomainDeceptionRaisesTheAlarmWithItsReason() {
        mapOf(
            "https://paypal.com.verify-account.example/login?otp=123456" to ReasonCode.BRAND_IN_SUBDOMAIN,
            "https://sbi.verify-kyc.example/account-update" to ReasonCode.BRAND_IN_SUBDOMAIN,
            "https://paypa1.example/verify" to ReasonCode.LOOKALIKE,
            "https://trusted.example@other.example/verify" to ReasonCode.USER_INFO
        ).forEach { (input, reason) ->
            val result = engine.evaluate(input, "")
            assertEquals(input, Level.DANGEROUS, result.level)
            assertTrue(input, reason in result.reasonCodes && ReasonCode.LOCAL_RISK in result.reasonCodes)
        }
    }

    @Test fun neutralSharingAndMarketingLanguageDoesNotWarn() {
        listOf(
            "Please click this link",
            "Check out this product:",
            "Hi Mom, here is the link you asked for",
            "Your Airtel recharge is successful. View details at",
            "Recharge now and get extra data with Airtel",
            "Get 20% off on your first order. Shop now"
        ).forEach { message ->
            val input = "$message https://weurl.co/GDMNKS/oo8a70"
            assertEquals(input, Level.SAFE, engine.evaluate("https://weurl.co/GDMNKS/oo8a70", input).level)
        }
    }

    @Test fun credentialPressureAlarmsWithoutALinkButOnlyCautionsOnFamiliarDomains() {
        val textOnly = engine.evaluateText(pressure)
        assertEquals(Level.DANGEROUS, textOnly.level)
        assertTrue(ReasonCode.CREDENTIAL_REQUEST in textOnly.reasonCodes)
        listOf("https://www.google.com/", "https://www.airtel.in/", "https://ordinary.example/").forEach { input ->
            val result = engine.evaluate(input, "$pressure $input")
            assertEquals(input, Level.SUSPICIOUS, result.level)
            assertEquals(input, setOf(ReasonCode.CREDENTIAL_REQUEST, ReasonCode.SCAM_LANGUAGE), result.reasonCodes - ReasonCode.TEST_INPUT)
        }
    }

    @Test fun ordinaryNotificationPipelineResultsRemainSilent() {
        val pipeline = MessagePipeline(engine)
        listOf("i.airtel.in/rc349sil3", "https://weurl.co/GDMNKS/oo8a70").forEach { input ->
            val results = pipeline.scan(input, "regression", false)
            assertEquals(1, results.size)
            assertTrue(results.all { it.verdict.level == Level.SAFE && it.verdict.reasonCodes.isEmpty() })
        }
    }

    @Test fun unreadableAddressAndScamWordingRemainACautionWithoutIntrusiveEscalation() {
        val result = engine.evaluate("https://example.org:70000", pressure)
        assertEquals(Level.SUSPICIOUS, result.level)
        assertEquals(setOf(ReasonCode.INVALID_URL, ReasonCode.CREDENTIAL_REQUEST, ReasonCode.SCAM_LANGUAGE), result.reasonCodes)
    }
}
