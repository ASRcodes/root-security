package org.suraksha.core

enum class CallRiskLevel { NONE, CAUTION, HIGH }
enum class CallReason { HIDDEN_NUMBER, REPORTED_NUMBER, INTERNATIONAL, UNKNOWN_CALLER, TELEMARKETING }
data class CallVerdict(val level: CallRiskLevel, val reasons: Set<CallReason>)

/** Caller-ID advice only. A high warning means a local user report, never verified fraud. */
object CallRisk {
    fun normalize(raw: String?): String {
        val kept = raw.orEmpty().filter { it.isDigit() || it == '+' }
        val plus = kept.startsWith("+")
        val digits = kept.filter(Char::isDigit)
        return when {
            plus -> "+$digits"
            digits.startsWith("00") -> "+" + digits.drop(2)
            else -> digits
        }
    }

    /** The national significant number, used to compare a caller with a user's scam list. */
    fun nationalKey(raw: String?, homeCountryCode: String = "91"): String {
        val n = normalize(raw)
        return when {
            n.startsWith("+$homeCountryCode") -> n.removePrefix("+$homeCountryCode")
            n.startsWith("+") -> n.drop(1)
            n.startsWith("0") -> n.trimStart('0')
            n.length > 10 && n.startsWith(homeCountryCode) -> n.removePrefix(homeCountryCode)
            else -> n
        }
    }

    /** One local comparison/storage key; explicit international prefixes retain their country. */
    fun reportKey(raw: String?, homeCountryCode: String = "91"): String? {
        val source = raw?.trim().orEmpty()
        if (source.isBlank() || source.any { it !in '0'..'9' && it !in "+()- ." } ||
            source.count { it == '+' } > 1 || ('+' in source && !source.startsWith('+'))) return null
        val n = normalize(source)
        val digits = if (n.startsWith('+')) n.drop(1) else nationalKey(n, homeCountryCode)
        if (digits.length !in 3..15 || digits.any { it !in '0'..'9' }) return null
        val key = if (n.startsWith('+')) "+$digits" else "+$homeCountryCode$digits"
        return key.takeIf { it.length <= 16 }
    }

    fun evaluate(number: String?, hidden: Boolean, knownContact: Boolean, homeCountryCode: String = "91",
                 reported: Set<String> = emptySet()): CallVerdict {
        if (knownContact) return CallVerdict(CallRiskLevel.NONE, emptySet())
        val n = normalize(number)
        if (hidden || n.isEmpty() || n == "+") return CallVerdict(CallRiskLevel.CAUTION, setOf(CallReason.HIDDEN_NUMBER))
        val key = reportKey(number, homeCountryCode)
        if (key != null && reported.any { reportKey(it, homeCountryCode) == key })
            return CallVerdict(CallRiskLevel.HIGH, setOf(CallReason.REPORTED_NUMBER))
        if (n.startsWith("+") && !n.startsWith("+$homeCountryCode")) {
            return CallVerdict(CallRiskLevel.CAUTION, setOf(CallReason.INTERNATIONAL))
        }
        val national = nationalKey(n, homeCountryCode)
        if (homeCountryCode == "91") {
            // TRAI: 1600-series numbers are reserved for regulated banking/financial institutions.
            if (national.startsWith("1600")) return CallVerdict(CallRiskLevel.NONE, emptySet())
            if (national.startsWith("140")) return CallVerdict(CallRiskLevel.CAUTION, setOf(CallReason.TELEMARKETING))
        }
        // Avoid routine advice on short service codes; this is not an authenticity guarantee.
        if (national.length in 3..6) return CallVerdict(CallRiskLevel.NONE, emptySet())
        return CallVerdict(CallRiskLevel.CAUTION, setOf(CallReason.UNKNOWN_CALLER))
    }
}
