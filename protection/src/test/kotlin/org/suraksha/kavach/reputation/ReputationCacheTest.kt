package org.suraksha.kavach.reputation

import java.net.URLDecoder
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.suraksha.core.SafeBrowsingMatch
import org.suraksha.core.SafeBrowsingReply

class ReputationCacheTest {
    private val full = "c9mG4AkGXxgsELy2pF2z1u2pSY+JMGVK8mU/ipOM2AE="
    private val prefix = hashPrefix(full)

    @Test fun onlyTheCompleteHashCanProduceAThreat() {
        val collisionBytes = Base64.getDecoder().decode(full).also { it[31] = (it[31].toInt() xor 1).toByte() }
        val collision = Base64.getEncoder().encodeToString(collisionBytes)
        val cache = ReputationCache()
        val records = cache.record(setOf(prefix), SafeBrowsingReply(listOf(SafeBrowsingMatch(collision, setOf("MALWARE"))), 300_000), 123, 0)
        assertEquals(prefix, hashPrefix(collision))
        assertEquals(ReputationStatus.NO_MATCH, reputationFor(setOf(full), records).status)
        assertEquals(ReputationResult(ReputationStatus.THREAT, setOf("MALWARE"), 123), reputationFor(setOf(collision), records))
        assertEquals(ReputationStatus.NOT_CHECKED, reputationFor(setOf(full), emptyMap()).status)
    }

    @Test fun negativeEntriesAreCachedAndExpireAtTheServerDuration() {
        val cache = ReputationCache()
        cache.record(setOf(prefix), SafeBrowsingReply(emptyList(), 3500), 100, 1000)
        assertEquals(ReputationStatus.NO_MATCH, reputationFor(setOf(full), cache.snapshot(setOf(prefix), 4499)).status)
        assertTrue(cache.snapshot(setOf(prefix), 4500).isEmpty())
    }

    @Test fun positivesNeverRemainFreshBeyondThirtyMinutes() {
        val cache = ReputationCache()
        cache.record(setOf(prefix), SafeBrowsingReply(listOf(SafeBrowsingMatch(full, setOf("SOCIAL_ENGINEERING"))), 86_400_000), 100, 500)
        assertFalse(cache.snapshot(setOf(prefix), 1_800_499).isEmpty())
        assertTrue(cache.snapshot(setOf(prefix), 1_800_500).isEmpty())
    }

    @Test fun aFreshThreatKeepsItsOwnTimestampAlongsideOlderNegativePrefixes() {
        val dangerous = "771MOrRPMn6xPKlCrXx/CrR+wmCk0LgFFoSgGy7zUiA="
        val cache = ReputationCache()
        cache.record(setOf(prefix), SafeBrowsingReply(emptyList(), 86_400_000), 1_000, 0)
        cache.record(setOf(hashPrefix(dangerous)),
            SafeBrowsingReply(listOf(SafeBrowsingMatch(dangerous, setOf("SOCIAL_ENGINEERING"))), 300_000), 2_001_000, 2_000_000)
        val records = cache.snapshot(setOf(prefix, hashPrefix(dangerous)), 2_000_001)
        val result = reputationFor(setOf(full, dangerous), records)
        assertEquals(ReputationStatus.THREAT, result.status)
        assertEquals(2_001_000L, result.checkedAt)
    }

    @Test fun clearAndClockRollbackCannotReuseOldResults() {
        val cache = ReputationCache()
        cache.record(setOf(prefix), SafeBrowsingReply(emptyList(), 300_000), 1000, 500)
        assertTrue(cache.snapshot(setOf(prefix), 499).isEmpty())
        cache.record(setOf(prefix), SafeBrowsingReply(emptyList(), 300_000), 1000, 500)
        cache.clear()
        assertTrue(cache.snapshot(setOf(prefix), 501).isEmpty())
    }

    @Test fun boundedCacheReturnsTheEntireCurrentBatchWithoutRetainingAllEntries() {
        val prefixes = (0..512).map { Base64.getEncoder().encodeToString(byteArrayOf((it ushr 8).toByte(), it.toByte(), 0, 0)) }.toSet()
        val cache = ReputationCache()
        val batch = cache.record(prefixes, SafeBrowsingReply(emptyList(), 1000), 100, 0)
        assertEquals(513, batch.size)
        assertEquals(512, cache.snapshot(prefixes, 1).size)
    }

    @Test fun zeroDurationRepliesAreNeverRetained() {
        val cache = ReputationCache()
        val batch = cache.record(setOf(prefix), SafeBrowsingReply(emptyList(), 0), 100, 0)
        assertEquals(ReputationStatus.NO_MATCH, reputationFor(setOf(full), batch).status)
        assertTrue(cache.snapshot(setOf(prefix), 0).isEmpty())
    }

    @Test fun buildsOnlyAnEncodedPrefixQueryAtTheFixedHttpsOrigin() {
        val prefixes = setOf("/////w==", "+/v7+w==")
        val endpoint = safeBrowsingEndpoint(prefixes)
        assertEquals("https", endpoint.protocol)
        assertEquals("safebrowsing.googleapis.com", endpoint.host)
        assertEquals("/v5/hashes:search", endpoint.path)
        assertFalse(endpoint.toString().contains("key="))
        assertEquals(prefixes, endpoint.query.split('&').filter { it.startsWith("hashPrefixes=") }
            .map { URLDecoder.decode(it.substringAfter('='), "UTF-8") }.toSet())
        assertTrue(endpoint.toString().length < 7500)
    }

    @Test fun rejectsInvalidOrOversizedPrefixRequestsBeforeNetworking() {
        listOf(emptySet(), setOf(full), setOf("?"), setOf("AAAA"),
            (0..180).map { Base64.getEncoder().encodeToString(byteArrayOf(it.toByte(), 0, 0, 0)) }.toSet()).forEach { prefixes ->
            try { safeBrowsingEndpoint(prefixes); fail("Expected invalid prefix request") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
