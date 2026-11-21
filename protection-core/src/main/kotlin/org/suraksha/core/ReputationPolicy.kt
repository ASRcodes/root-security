package org.suraksha.core

/** A fresh exact provider match verifies a listed threat; LOCAL_RISK remains a separate, unconfirmed assessment. */
object ReputationPolicy {
    const val MAX_AGE_MILLIS = 30 * 60_000L
    private val reputationCodes = setOf(ReasonCode.GOOGLE_REPUTATION, ReasonCode.REPUTATION_NO_MATCH,
        ReasonCode.REPUTATION_NOT_CHECKED, ReasonCode.PROVIDER_TEST)

    fun verifiedThreat(local: Verdict, isProviderTest: Boolean = false): Verdict =
        Verdict(Level.DANGEROUS, 1.0, linkedSetOf(ReasonCode.GOOGLE_REPUTATION).apply {
            if (isProviderTest) add(ReasonCode.PROVIDER_TEST)
            addAll(local.reasonCodes - reputationCodes)
        })

    fun noMatch(local: Verdict): Verdict = local.copy(reasonCodes =
        linkedSetOf(ReasonCode.REPUTATION_NO_MATCH).apply { addAll(local.reasonCodes - reputationCodes) })

    fun notChecked(local: Verdict): Verdict = local.copy(reasonCodes =
        linkedSetOf(ReasonCode.REPUTATION_NOT_CHECKED).apply { addAll(local.reasonCodes - reputationCodes) })

    /** The timestamp on a positive result is the provider lookup time, including cache hits. */
    fun expire(result: ScanResult, now: Long): ScanResult {
        if (result.verdict.level == Level.DANGEROUS && ReasonCode.GOOGLE_REPUTATION !in result.verdict.reasonCodes &&
            ReasonCode.FILE_SCAN !in result.verdict.reasonCodes && ReasonCode.DEMO_ONLY !in result.verdict.reasonCodes &&
            ReasonCode.LOCAL_RISK !in result.verdict.reasonCodes) {
            // Pre-upgrade URL model/list records cannot be replayed as verified live threats.
            return result.copy(verdict = Verdict(Level.SAFE, 0.0, setOf(ReasonCode.REPUTATION_NOT_CHECKED)))
        }
        if (ReasonCode.GOOGLE_REPUTATION !in result.verdict.reasonCodes) return result
        if (now >= result.timestamp && now - result.timestamp < MAX_AGE_MILLIS) return result
        return result.copy(verdict = Verdict(Level.SAFE, 0.0,
            (result.verdict.reasonCodes - ReasonCode.GOOGLE_REPUTATION - ReasonCode.REPUTATION_NO_MATCH) +
                ReasonCode.REPUTATION_NOT_CHECKED))
    }

    /** Labels Google's documented harmless fixtures; this never supplies a verdict. */
    fun isProviderTestUrl(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.host.equals("testsafebrowsing.appspot.com", ignoreCase = true) &&
            uri.path in setOf("/s/phishing.html", "/s/malware.html")
    }.getOrDefault(false)
}
