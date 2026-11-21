package org.suraksha.kavach.reputation

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SafeBrowsingHttpDiagnosticsTest {
    private fun detail(reason: String, domain: String = "googleapis.com",
                       type: String = "type.googleapis.com/google.rpc.ErrorInfo") = buildJsonObject {
        put("@type", type); put("domain", domain); put("reason", reason)
        putJsonObject("metadata") { put("consumer", "private-project-marker"); put("key", "private-key-marker") }
    }
    private fun reply(vararg details: JsonObject, message: String = "private-message-marker") = buildJsonObject {
        putJsonObject("error") {
            put("code", 403); put("message", message); put("details", JsonArray(details.toList()))
        }
    }.toString().toByteArray()

    @Test fun recognizedReasonsProduceSpecificFixedAdviceWithoutResponseData() {
        val expected = mapOf(
            GoogleApiErrorReason.SERVICE_DISABLED to "Enable Safe Browsing API",
            GoogleApiErrorReason.API_KEY_SERVICE_BLOCKED to "API restrictions",
            GoogleApiErrorReason.API_KEY_ANDROID_APP_BLOCKED to "Android app restriction",
            GoogleApiErrorReason.API_KEY_HTTP_REFERRER_BLOCKED to "website referrer restriction",
            GoogleApiErrorReason.API_KEY_IP_ADDRESS_BLOCKED to "allowed IP addresses",
            GoogleApiErrorReason.API_KEY_INVALID to "invalid"
        )
        for ((reason, phrase) in expected) {
            val parsed = SafeBrowsingHttpDiagnostics.reason(reply(detail(reason.name)))
            assertEquals(reason, parsed)
            val failure = ReputationHttpException(403, parsed)
            val advice = SafeBrowsingHttpDiagnostics.statusMessage(failure)
            assertTrue(advice.contains(phrase))
            assertTrue(advice.endsWith("not checked"))
            assertFalse(advice.contains("private-"))
            assertFalse(failure.toString().contains("private-"))
            assertNull(failure.cause)
        }
    }

    @Test fun exactGoogleErrorInfoTypeAndDomainAreRequired() {
        listOf(
            detail("SERVICE_DISABLED", domain = "attacker.example"),
            detail("SERVICE_DISABLED", domain = "googleapis.com.attacker.example"),
            detail("SERVICE_DISABLED", type = "google.rpc.ErrorInfo"),
            detail("SERVICE_DISABLED", type = "type.googleapis.com/google.rpc.Help"),
            detail("service_disabled"),
            detail("UNKNOWN_REASON_WITH_PRIVATE_DATA")
        ).forEach { assertNull(SafeBrowsingHttpDiagnostics.reason(reply(it))) }
    }

    @Test fun FreeformMessageAndMetadataCannotSupplyAnErrorReason() {
        val body = """{"error":{"message":"SERVICE_DISABLED private-key-marker","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","domain":"googleapis.com","metadata":{"reason":"API_KEY_INVALID"}}]}}"""
        assertNull(SafeBrowsingHttpDiagnostics.reason(body.toByteArray()))
        assertEquals("Check the API setup or quota — not checked",
            SafeBrowsingHttpDiagnostics.statusMessage(ReputationHttpException(403)))
    }

    @Test fun malformedJsonWrongShapesAndInvalidUtf8FallBackToKnownHttpStatus() {
        listOf("", "not json private-key-marker", "{", "[]", "null", "{\"error\":[]}",
            "{\"error\":{\"details\":{}}}",
            "{\"error\":{\"details\":[{\"@type\":true,\"domain\":\"googleapis.com\",\"reason\":\"SERVICE_DISABLED\"}]}}")
            .map { it.toByteArray() }.plus(byteArrayOf(0xC3.toByte(), 0x28)).forEach { bytes ->
                val parsed = SafeBrowsingHttpDiagnostics.reason(bytes)
                assertNull(parsed)
                assertEquals("Check the API setup or quota — not checked",
                    SafeBrowsingHttpDiagnostics.statusMessage(ReputationHttpException(403, parsed)))
            }
    }

    @Test fun bodySizeDepthAndDetailCountsAreBoundedBeforeAcceptingAdvice() {
        val small = reply(detail("SERVICE_DISABLED"))
        val atLimit = small + ByteArray(SafeBrowsingHttpDiagnostics.MAX_ERROR_BYTES - small.size) { 32 }
        assertEquals(GoogleApiErrorReason.SERVICE_DISABLED, SafeBrowsingHttpDiagnostics.reason(atLimit))
        assertNull(SafeBrowsingHttpDiagnostics.reason(atLimit + byteArrayOf(32)))
        assertNull(SafeBrowsingHttpDiagnostics.reason(("[".repeat(17) + "0" + "]".repeat(17)).toByteArray()))
        assertNull(SafeBrowsingHttpDiagnostics.reason(reply(*Array(17) { detail("SERVICE_DISABLED") })))
    }

    @Test fun quotedBracesAndEscapesDoNotConfuseTheDepthGuard() {
        assertEquals(GoogleApiErrorReason.API_KEY_INVALID, SafeBrowsingHttpDiagnostics.reason(
            reply(detail("API_KEY_INVALID"), message = "[[[[ {{{{ \\\" private-message-marker")))
    }

    @Test fun conflictingKnownReasonsFallBackInsteadOfInventingOneSettingFix() {
        assertNull(SafeBrowsingHttpDiagnostics.reason(reply(detail("SERVICE_DISABLED"), detail("API_KEY_INVALID"))))
        assertEquals(GoogleApiErrorReason.SERVICE_DISABLED,
            SafeBrowsingHttpDiagnostics.reason(reply(detail("SERVICE_DISABLED"), detail("SERVICE_DISABLED"))))
    }

    @Test fun unrelatedHttpStatusesKeepTheirExistingMeaning() {
        assertEquals("Rate limit reached — not checked", SafeBrowsingHttpDiagnostics.statusMessage(
            ReputationHttpException(429, GoogleApiErrorReason.SERVICE_DISABLED)))
        assertEquals("Lookup unavailable — not checked", SafeBrowsingHttpDiagnostics.statusMessage(
            ReputationHttpException(500, GoogleApiErrorReason.API_KEY_INVALID)))
        assertEquals("API key was not accepted — not checked", SafeBrowsingHttpDiagnostics.statusMessage(ReputationHttpException(401)))
    }
}
