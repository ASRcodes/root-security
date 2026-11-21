package org.suraksha.core

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SafeBrowsingResponseTest {
    private val hash = "c9mG4AkGXxgsELy2pF2z1u2pSY+JMGVK8mU/ipOM2AE="

    @Test fun acceptsAnExplicitOrOmittedEmptyHashList() {
        assertEquals(SafeBrowsingReply(emptyList(), 300_000), SafeBrowsingResponse.parse("""{"cacheDuration":"300s"}"""))
        assertEquals(SafeBrowsingReply(emptyList(), 300_000), SafeBrowsingResponse.parse("""{"cacheDuration":"300s","fullHashes":[]}"""))
    }

    @Test fun returnsKnownTypesAndMergesDuplicateFullHashes() {
        val json = reply(listOf(
            entry(hash, """{"threatType":"MALWARE"},{"threatType":"SOCIAL_ENGINEERING","attributes":[]}"""),
            entry(hash, """{"threatType":"UNWANTED_SOFTWARE"},{"threatType":"POTENTIALLY_HARMFUL_APPLICATION"}""")
        ).joinToString(","))
        assertEquals(listOf(SafeBrowsingMatch(hash, setOf("MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION"))),
            SafeBrowsingResponse.parse(json).matches)
    }

    @Test fun ignoresUnenforceableDetailsWithoutDiscardingOtherValidDetails() {
        val details = listOf(
            """{"threatType":"FUTURE_THREAT"}""",
            """{"threatType":"THREAT_TYPE_UNSPECIFIED"}""",
            """{"threatType":"MALWARE","attributes":["CANARY"]}""",
            """{"threatType":"MALWARE","attributes":["FRAME_ONLY"]}""",
            """{"threatType":"MALWARE","attributes":["THREAT_ATTRIBUTE_UNSPECIFIED"]}""",
            """{"threatType":"MALWARE","attributes":["NEW_ATTRIBUTE"]}""",
            """{"threatType":"SOCIAL_ENGINEERING"}"""
        ).joinToString(",")
        assertEquals(listOf(SafeBrowsingMatch(hash, setOf("SOCIAL_ENGINEERING"))),
            SafeBrowsingResponse.parse(reply(entry(hash, details))).matches)
        assertTrue(SafeBrowsingResponse.parse(reply(entry(hash, """{"threatType":"MALWARE","attributes":["CANARY"]}"""))).matches.isEmpty())
    }

    @Test fun ignoresUnknownFieldsAtEveryLevel() {
        val json = """{"cacheDuration":"1s","future":{"data":[1,true]},"fullHashes":[{"fullHash":"$hash","newHashField":5,"fullHashDetails":[{"threatType":"MALWARE","newDetailField":"future"}]}]}"""
        assertEquals(listOf(SafeBrowsingMatch(hash, setOf("MALWARE"))), SafeBrowsingResponse.parse(json).matches)
    }

    @Test fun normalizesAllProtobufBase64VariantsForReliableFullHashComparison() {
        listOf(hash, hash.trimEnd('='), hash.replace('+', '-').replace('/', '_'),
            hash.replace('+', '-').replace('/', '_').trimEnd('=')).forEach { encoded ->
            assertEquals(hash, SafeBrowsingResponse.parse(reply(entry(encoded))).matches.single().fullHash)
        }
    }

    @Test fun retainsCompleteHashForPrefixCollisionComparisonByCaller() {
        val collision = Base64.getDecoder().decode(hash).also { it[31] = (it[31].toInt() xor 1).toByte() }
        val other = Base64.getEncoder().encodeToString(collision)
        assertArrayEquals(Base64.getDecoder().decode(hash).copyOf(4), collision.copyOf(4))
        assertNotEquals(hash, other)
        val parsed = SafeBrowsingResponse.parse(reply(entry(other))).matches.single()
        assertEquals(other, parsed.fullHash)
        assertFalse(parsed.fullHash in setOf(hash))
    }

    @Test fun floorsFractionalSecondsAndCapsCacheWithoutOverflow() {
        mapOf("0s" to 0L, "0.000000001s" to 0L, "0.999999999s" to 999L,
            "3.5s" to 3500L, "3.123456789s" to 3123L, "86399.999999999s" to 86_399_999L,
            "86400s" to 86_400_000L, "315576000000s" to 86_400_000L).forEach { (duration, millis) ->
            assertEquals(duration, millis, SafeBrowsingResponse.parse(reply(duration = duration)).cacheMillis)
        }
    }

    @Test fun rejectsMissingMalformedNegativeOrOutOfRangeCacheDurations() {
        listOf("{}", """{"cacheDuration":null}""", """{"cacheDuration":300}""", """{"cacheDuration":{}}""").forEach(::assertInvalid)
        listOf("", "300", "300ms", "-1s", "-0.001s", "+1s", "NaNs", "Infinitys", "1e3s", "1.s",
            ".1s", "1.1234567890s", " 1s", "1s ", "315576000001s", "99999999999999999999999999999999999s").forEach {
            assertInvalid(reply(duration = it))
        }
    }

    @Test fun rejectsMalformedSchemaInsteadOfReturningAnEmptyCleanResult() {
        listOf("", "[]", "null", "42", "true", "{", """{"cacheDuration":"1s","fullHashes":null}""",
            """{"cacheDuration":"1s","fullHashes":{}}""", """{"cacheDuration":"1s","fullHashes":""}""",
            reply("null"), reply("[]"), reply("42"), reply("{}"),
            reply("""{"fullHash":null}"""), reply("""{"fullHash":"$hash","fullHashDetails":{}}"""),
            reply("""{"fullHash":"$hash","fullHashDetails":null}"""),
            reply(entry(hash, "null")), reply(entry(hash, "[]")), reply(entry(hash, "{}")),
            reply(entry(hash, """{"threatType":null}""")), reply(entry(hash, """{"threatType":3}""")),
            reply(entry(hash, """{"threatType":"MALWARE","attributes":"CANARY"}""")),
            reply(entry(hash, """{"threatType":"MALWARE","attributes":[{}]}""")),
            reply(entry(hash, """{"threatType":"MALWARE","attributes":[null]}"""))).forEach(::assertInvalid)
    }

    @Test fun rejectsMalformedHashesEvenWhenTheirDetailsAreIgnored() {
        val invalid = listOf("", "c9mG4A==", "?".repeat(43) + "=", hash + "=", hash + "\n",
            hash.replace('=', 'A'), hash.dropLast(2) + "F=", "A".repeat(42) + "==")
        invalid.forEach { encoded ->
            // Escape newlines so the test reaches hash validation rather than JSON syntax rejection.
            assertInvalid(reply(entry(encoded.replace("\n", "\\n"), """{"threatType":"MALWARE","attributes":["CANARY"]}""")))
        }
    }

    @Test fun enforcesResponseHashAndDetailBounds() {
        assertInvalid("""{"cacheDuration":"1s","unknown":"${"a".repeat(1_048_576)}"}""")
        assertInvalid("""{"cacheDuration":"1s","unknown":"${"é".repeat(524_289)}"}""")
        val bare = """{"fullHash":"$hash"}"""
        assertTrue(SafeBrowsingResponse.parse(reply(List(10_000) { bare }.joinToString(","))).matches.isEmpty())
        assertInvalid(reply(List(10_001) { bare }.joinToString(",")))
        val detail = """{"threatType":"MALWARE"}"""
        assertEquals(1, SafeBrowsingResponse.parse(reply(entry(hash, List(64) { detail }.joinToString(",")))).matches.size)
        assertInvalid(reply(entry(hash, List(65) { detail }.joinToString(","))))
        assertInvalid("""{"cacheDuration":"1s","nested":${"[".repeat(33)}0${"]".repeat(33)}}""")
    }

    private fun entry(encoded: String, details: String = """{"threatType":"MALWARE"}"""): String =
        """{"fullHash":"$encoded","fullHashDetails":[$details]}"""

    private fun reply(hashes: String = "", duration: String = "300s"): String =
        """{"cacheDuration":"$duration","fullHashes":[$hashes]}"""

    private fun assertInvalid(json: String) {
        try {
            SafeBrowsingResponse.parse(json)
            fail("Expected invalid response: ${json.take(120)}")
        } catch (_: IllegalArgumentException) {
            // The caller must translate invalid responses into Not checked, never a clean verdict.
        }
    }
}
