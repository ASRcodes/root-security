package org.suraksha.core

import org.junit.Assert.*
import org.junit.Test

/** Pure policy tests: no provider request or live URL is involved. */
class ReputationPolicyTest {
    private val ordinary = Verdict(Level.SAFE, 0.0, emptySet())
    private val caution = Verdict(Level.SUSPICIOUS, 0.9, setOf(ReasonCode.SCAM_LANGUAGE))

    @Test fun exactProviderMatchIsTheOnlyPolicyStepThatEscalatesAnOrdinaryLink() {
        assertEquals(Level.SAFE, ReputationPolicy.notChecked(ordinary).level)
        assertEquals(Level.SAFE, ReputationPolicy.noMatch(ordinary).level)
        val matched = ReputationPolicy.verifiedThreat(ordinary)
        assertEquals(Level.DANGEROUS, matched.level)
        assertEquals(setOf(ReasonCode.GOOGLE_REPUTATION), matched.reasonCodes)
    }

    @Test fun noMatchAndUnavailablePreserveLocalCautionWithoutInventingProof() {
        val noMatch = ReputationPolicy.noMatch(caution)
        assertEquals(Level.SUSPICIOUS, noMatch.level)
        assertEquals(caution.score, noMatch.score, 0.0)
        assertEquals(setOf(ReasonCode.SCAM_LANGUAGE, ReasonCode.REPUTATION_NO_MATCH), noMatch.reasonCodes)
        val unavailable = ReputationPolicy.notChecked(caution)
        assertEquals(Level.SUSPICIOUS, unavailable.level)
        assertEquals(setOf(ReasonCode.SCAM_LANGUAGE, ReasonCode.REPUTATION_NOT_CHECKED), unavailable.reasonCodes)
    }

    @Test fun replacingProviderStatusDoesNotAccumulateContradictoryReasons() {
        val checked = ReputationPolicy.noMatch(ReputationPolicy.notChecked(caution))
        assertFalse(ReasonCode.REPUTATION_NOT_CHECKED in checked.reasonCodes)
        val unavailable = ReputationPolicy.notChecked(checked)
        assertFalse(ReasonCode.REPUTATION_NO_MATCH in unavailable.reasonCodes)
        val verified = ReputationPolicy.verifiedThreat(unavailable, isProviderTest = true)
        assertEquals(setOf(ReasonCode.GOOGLE_REPUTATION, ReasonCode.PROVIDER_TEST, ReasonCode.SCAM_LANGUAGE), verified.reasonCodes)
    }

    @Test fun officialTestLabelsRequireProviderEvidenceToBecomeDangerous() {
        assertTrue(ReputationPolicy.isProviderTestUrl("https://testsafebrowsing.appspot.com/s/phishing.html"))
        assertFalse(ReputationPolicy.isProviderTestUrl("https://testsafebrowsing.appspot.com.evil.example/s/phishing.html"))
        assertFalse(ReputationPolicy.isProviderTestUrl("https://other.example/s/phishing.html"))
        assertFalse(ReputationPolicy.isProviderTestUrl("https://testsafebrowsing.appspot.com/ordinary"))
        assertEquals(setOf(ReasonCode.REPUTATION_NOT_CHECKED), ReputationPolicy.notChecked(ordinary).reasonCodes)
        assertEquals(setOf(ReasonCode.GOOGLE_REPUTATION, ReasonCode.PROVIDER_TEST),
            ReputationPolicy.verifiedThreat(ordinary, isProviderTest = true).reasonCodes)
    }

    @Test fun providerEvidenceRemainsValidOnlyWithinItsThirtyMinuteWindow() {
        val timestamp = 10_000L
        val result = ScanResult("ordinary.example", ReputationPolicy.verifiedThreat(ordinary), timestamp)
        assertEquals(result, ReputationPolicy.expire(result, timestamp))
        assertEquals(result, ReputationPolicy.expire(result, timestamp + ReputationPolicy.MAX_AGE_MILLIS - 1))
        listOf(timestamp - 1, timestamp + ReputationPolicy.MAX_AGE_MILLIS,
            timestamp + ReputationPolicy.MAX_AGE_MILLIS + 1).forEach { now ->
            val expired = ReputationPolicy.expire(result, now)
            assertEquals(Level.SAFE, expired.verdict.level)
            assertEquals(setOf(ReasonCode.REPUTATION_NOT_CHECKED), expired.verdict.reasonCodes)
            assertEquals(timestamp, expired.timestamp)
            assertEquals(result.domain, expired.domain)
        }
    }

    @Test fun expiryNeverTurnsAnOldLocalCautionIntoASafetyClaim() {
        val local = ScanResult("ordinary.example", caution, 1L)
        assertEquals(local, ReputationPolicy.expire(local, ReputationPolicy.MAX_AGE_MILLIS + 100))
    }

    @Test fun preUpgradeDangerRecordsCannotReplayAsCurrentVerifiedThreats() {
        listOf(emptySet(), setOf(ReasonCode.URL_MODEL), setOf(ReasonCode.KNOWN_PHISHING),
            setOf(ReasonCode.SCAM_LANGUAGE, ReasonCode.URL_MODEL)).forEach { reasons ->
            val legacy = ScanResult("ordinary.example", Verdict(Level.DANGEROUS, 1.0, reasons), 100L)
            val current = ReputationPolicy.expire(legacy, 101L)
            assertEquals(Level.SAFE, current.verdict.level)
            assertEquals(setOf(ReasonCode.REPUTATION_NOT_CHECKED), current.verdict.reasonCodes)
        }
    }

    @Test fun urlHistoryMigrationDoesNotEraseFileOrExplicitPreviewSemantics() {
        listOf(setOf(ReasonCode.FILE_SCAN, ReasonCode.EICAR_TEST), setOf(ReasonCode.DEMO_ONLY)).forEach { reasons ->
            val result = ScanResult("fixture.example", Verdict(Level.DANGEROUS, 1.0, reasons), 100L)
            assertEquals(result, ReputationPolicy.expire(result, ReputationPolicy.MAX_AGE_MILLIS + 101L))
        }
    }

    @Test fun pipelineAppliesReputationPerUrlAndReturnsOnlyMetadata() {
        val pipeline = MessagePipeline(UrlScorer { _, _ -> ordinary }) { 100L }
        val inputs = mutableListOf<String>()
        val result = pipeline.scan("https://one.example/private/path?token=fixture https://two.example/", "test", false) { url, local ->
            inputs += url
            local.copy(verdict = if (url.contains("one.example")) ReputationPolicy.noMatch(local.verdict)
                else ReputationPolicy.notChecked(local.verdict))
        }
        assertEquals(2, inputs.size)
        assertEquals(listOf("one.example", "two.example"), result.map { it.domain })
        assertTrue(ReasonCode.REPUTATION_NO_MATCH in result[0].verdict.reasonCodes)
        assertTrue(ReasonCode.REPUTATION_NOT_CHECKED in result[1].verdict.reasonCodes)
        assertFalse(result.toString().contains("private/path"))
        assertFalse(result.toString().contains("token=fixture"))
    }

    @Test fun duplicateMessagesDoNotInvokeTheProviderTransformAgain() {
        val pipeline = MessagePipeline(UrlScorer { _, _ -> ordinary }) { 100L }
        var calls = 0
        val transform: (String, ScanResult) -> ScanResult = { _, local -> calls++; local }
        assertEquals(1, pipeline.scan("https://one.example/", "same-source", transform = transform).size)
        assertTrue(pipeline.scan("https://one.example/", "same-source", transform = transform).isEmpty())
        assertEquals(1, calls)
    }

    @Test fun textOnlyMessagesNeverReachTheUrlReputationTransform() {
        val scorer = object : UrlScorer {
            override fun evaluate(url: String, message: String) = ordinary
            override fun evaluateText(message: String) = caution
        }
        val result = MessagePipeline(scorer).scan("Please share your OTP immediately", "test", false) { _, _ ->
            fail("Message bodies must not be sent to the URL reputation transform")
            error("Unexpected URL callback")
        }.single()
        assertEquals("", result.domain)
        assertEquals(caution, result.verdict)
    }
}
