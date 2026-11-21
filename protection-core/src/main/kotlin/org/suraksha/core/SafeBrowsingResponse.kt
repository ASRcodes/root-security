package org.suraksha.core

import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class SafeBrowsingMatch(val fullHash: String, val threatTypes: Set<String>)
data class SafeBrowsingReply(val matches: List<SafeBrowsingMatch>, val cacheMillis: Long)

/**
 * Parses v5 hashes.search responses; malformed replies must become Not checked at the call site.
 * Returned hashes are candidates, not URL verdicts: callers must compare the complete local hash.
 * https://developers.google.com/safe-browsing/reference/rest/v5/hashes/search
 */
object SafeBrowsingResponse {
    fun parse(json: String): SafeBrowsingReply {
        require(json.length <= MAX_RESPONSE_BYTES && json.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
            "Safe Browsing response exceeds the size limit"
        }
        checkDepth(json)
        val document = try {
            Json.parseToJsonElement(json)
        } catch (_: SerializationException) {
            throw IllegalArgumentException("Malformed Safe Browsing JSON")
        }
        val root = document as? JsonObject ?: throw IllegalArgumentException("Safe Browsing response must be an object")
        val cacheMillis = parseDuration(string(root["cacheDuration"], "cacheDuration"))
        val hashes = array(root, "fullHashes")
        require(hashes.size <= MAX_MATCHES) { "Too many Safe Browsing full hashes" }
        val matches = linkedMapOf<String, MutableSet<String>>()
        hashes.forEach { element ->
            val hash = element as? JsonObject ?: throw IllegalArgumentException("fullHashes entries must be objects")
            val fullHash = normalizeHash(string(hash["fullHash"], "fullHash"))
            val details = array(hash, "fullHashDetails")
            require(details.size <= MAX_DETAILS) { "Too many Safe Browsing threat details" }
            details.forEach { item ->
                val detail = item as? JsonObject ?: throw IllegalArgumentException("Threat details must be objects")
                val type = string(detail["threatType"], "threatType")
                val attributes = array(detail, "attributes").map { string(it, "attribute") }
                // Every currently defined attribute prevents enforcement for a top-level message
                // link: UNSPECIFIED, CANARY, FRAME_ONLY. Unknown attributes discard the detail too.
                if (type in THREAT_TYPES && attributes.isEmpty()) {
                    matches.getOrPut(fullHash) { linkedSetOf() }.add(type)
                }
            }
        }
        return SafeBrowsingReply(matches.map { SafeBrowsingMatch(it.key, it.value.toSet()) }, cacheMillis)
    }

    private fun array(obj: JsonObject, field: String): JsonArray {
        val element = obj[field] ?: return JsonArray(emptyList())
        return element as? JsonArray ?: throw IllegalArgumentException("$field must be an array")
    }

    private fun string(element: JsonElement?, field: String): String {
        val primitive = element as? JsonPrimitive
        require(primitive != null && primitive.isString) { "$field must be a string" }
        return primitive.content
    }

    private fun normalizeHash(encoded: String): String {
        // ProtoJSON bytes accept standard or URL-safe base64, with or without padding.
        require(STANDARD_HASH.matches(encoded) || URL_SAFE_HASH.matches(encoded)) { "Invalid fullHash encoding" }
        val standard = encoded.replace('-', '+').replace('_', '/')
        val bytes = Base64.getDecoder().decode(standard)
        require(bytes.size == 32) { "fullHash must contain exactly 32 bytes" }
        val canonical = Base64.getEncoder().encodeToString(bytes)
        require(canonical.trimEnd('=') == standard.trimEnd('=')) { "Invalid fullHash padding bits" }
        return canonical
    }

    private fun parseDuration(value: String): Long {
        require(value.length <= 32) { "Invalid cacheDuration" }
        val match = DURATION.matchEntire(value) ?: throw IllegalArgumentException("Invalid cacheDuration")
        val seconds = match.groupValues[1].toLongOrNull() ?: throw IllegalArgumentException("cacheDuration is out of range")
        require(seconds <= MAX_PROTOBUF_DURATION_SECONDS) { "cacheDuration is out of range" }
        // Cap before multiplication so neither long durations nor fractional seconds can overflow.
        if (seconds >= MAX_CACHE_SECONDS) return MAX_CACHE_SECONDS * 1000
        val fractionalMillis = match.groupValues[2].take(3).padEnd(3, '0').toInt()
        return seconds * 1000 + fractionalMillis
    }

    private fun checkDepth(json: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        json.forEach { character ->
            if (quoted) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                }
            } else {
                when (character) {
                    '"' -> quoted = true
                    '{', '[' -> require(++depth <= 32) { "Safe Browsing response is too deeply nested" }
                    '}', ']' -> depth--
                }
            }
        }
    }

    private const val MAX_RESPONSE_BYTES = 1_048_576
    private const val MAX_MATCHES = 10_000
    private const val MAX_DETAILS = 64
    private const val MAX_CACHE_SECONDS = 86_400L
    private const val MAX_PROTOBUF_DURATION_SECONDS = 315_576_000_000L
    private val DURATION = Regex("([0-9]+)(?:\\.([0-9]{1,9}))?s")
    private val STANDARD_HASH = Regex("[A-Za-z0-9+/]{43}=?")
    private val URL_SAFE_HASH = Regex("[A-Za-z0-9_-]{43}=?")
    private val THREAT_TYPES = setOf("MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION")
}
