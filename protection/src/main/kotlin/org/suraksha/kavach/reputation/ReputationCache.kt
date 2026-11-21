package org.suraksha.kavach.reputation

import java.util.Base64
import org.suraksha.core.SafeBrowsingMatch
import org.suraksha.core.SafeBrowsingReply

internal fun hashPrefix(fullHash: String): String =
    Base64.getEncoder().encodeToString(Base64.getDecoder().decode(fullHash).copyOf(4))

internal data class PrefixRecord(
    val matches: List<SafeBrowsingMatch>,
    val checkedAt: Long,
    val receivedElapsed: Long,
    val lifetimeMillis: Long
) {
    fun fresh(nowElapsed: Long): Boolean = nowElapsed >= receivedElapsed && nowElapsed - receivedElapsed < lifetimeMillis
}

/** Accessed only under OnlineUrlChecker's state lock. Contains no URLs or API keys. */
internal class ReputationCache(private val maximum: Int = 512) {
    private val values = LinkedHashMap<String, PrefixRecord>(64, 0.75f, true)

    fun clear() = values.clear()

    fun snapshot(prefixes: Set<String>, nowElapsed: Long): Map<String, PrefixRecord> = buildMap {
        prefixes.forEach { prefix ->
            val record = this@ReputationCache.values[prefix]
            if (record != null && record.fresh(nowElapsed)) put(prefix, record)
            else this@ReputationCache.values.remove(prefix)
        }
    }

    /** Returns this batch even when the bounded cache cannot retain every prefix. */
    fun record(prefixes: Set<String>, reply: SafeBrowsingReply, checkedAt: Long, nowElapsed: Long): Map<String, PrefixRecord> {
        val grouped = reply.matches.groupBy { hashPrefix(it.fullHash) }
        val batch = prefixes.associateWith { prefix ->
            val matches = grouped[prefix].orEmpty()
            val maximumAge = if (matches.isEmpty()) DAY_MILLIS else POSITIVE_MILLIS
            PrefixRecord(matches, checkedAt, nowElapsed, reply.cacheMillis.coerceIn(0, maximumAge))
        }
        batch.forEach { (prefix, value) ->
            values.remove(prefix)
            if (value.lifetimeMillis > 0) values[prefix] = value
            while (values.size > maximum) values.remove(values.keys.first())
        }
        return batch
    }

    private companion object {
        const val DAY_MILLIS = 86_400_000L
        const val POSITIVE_MILLIS = 1_800_000L
    }
}

/** A matching four-byte prefix is only a candidate; all 32 bytes must match before warning. */
internal fun reputationFor(fullHashes: Set<String>, records: Map<String, PrefixRecord>): ReputationResult {
    val required = fullHashes.map(::hashPrefix).toSet()
    if (required.isEmpty() || required.any { it !in records }) return ReputationResult(ReputationStatus.NOT_CHECKED)
    val applicable = required.map { records.getValue(it) }
    val matchedRecords = applicable.filter { record -> record.matches.any { it.fullHash in fullHashes && it.threatTypes.isNotEmpty() } }
    val threats = matchedRecords.flatMap { record -> record.matches.filter { it.fullHash in fullHashes }.flatMap { it.threatTypes } }.toSet()
    val checkedAt = (if (threats.isEmpty()) applicable else matchedRecords).minOf { it.checkedAt }
    return ReputationResult(if (threats.isEmpty()) ReputationStatus.NO_MATCH else ReputationStatus.THREAT, threats, checkedAt)
}
