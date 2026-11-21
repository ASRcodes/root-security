package org.suraksha.core

/**
 * Explainable local verdicts from independent signals (see [UrlRiskAnalyzer] and [ScamLanguage]).
 *
 * The bundled URL forest learned a path-format shortcut (ordinary deep links score 1.0),
 * so its score is never used. Google Safe Browsing, when configured, can still escalate a
 * link that local signals miss; a no-match reply never downgrades corroborated local evidence.
 * Local danger is an unconfirmed risk assessment, never a provider-verified threat.
 */
class VerdictEngine(
    @Suppress("unused") private val allowlist: Allowlist,
    blocklist: Blocklist,
    features: UrlFeatures,
    @Suppress("unused") private val urlModel: UrlModel,
    private val textModel: TextModel
) : UrlScorer {
    private val urls = UrlRiskAnalyzer(features.suffixes, blocklist)

    override fun evaluate(url: String, message: String): Verdict =
        ExampleLinks.markTestInput(url, evaluateWithText(url, message, textModel.score(message)))
    override fun evaluateBatch(urls: List<String>, message: String): List<Verdict> {
        val textScore = textModel.score(message)
        return urls.map { ExampleLinks.markTestInput(it, evaluateWithText(it, message, textScore)) }
    }

    override fun evaluateText(message: String): Verdict {
        val text = ScamLanguage.analyze(message, textModel.score(message))
        val level = when {
            text.raw >= RiskThresholds.TEXT_ONLY_DANGEROUS && text.asksForCredentials &&
                text.categories >= 2 && !text.educationalContext -> Level.DANGEROUS
            text.raw >= RiskThresholds.SUSPICIOUS -> Level.SUSPICIOUS
            else -> Level.SAFE
        }
        return verdict(level, text.raw, text.analysis.reasons)
    }

    private fun evaluateWithText(input: String, message: String, textScore: Double): Verdict {
        val text = ScamLanguage.analyze(message, textScore)
        val url = UrlParser.parse(input)
            ?: return Verdict(Level.SUSPICIOUS, maxOf(0.5, text.analysis.score / 100.0),
                (if (text.analysis.score >= RiskThresholds.SUSPICIOUS) text.analysis.reasons else emptySet()) + ReasonCode.INVALID_URL)
        val link = urls.analyze(url)
        var total = link.analysis.score + text.analysis.score
        val reasons = linkedSetOf<ReasonCode>().apply { addAll(link.analysis.reasons); addAll(text.analysis.reasons) }
        // "Your SBI account is blocked, update KYC: <some other site>" is a classic impersonation.
        if (!link.trusted && text.categories > 0 && ScamLanguage.mentionedBrands(message).any { brand ->
                link.registrable !in UrlRiskAnalyzer.BRANDS[brand].orEmpty() }) {
            total += 20
            reasons += ReasonCode.BRAND_MISMATCH
        }
        val linkReasons = link.analysis.reasons
        val deception = linkReasons.any { it in setOf(ReasonCode.LOOKALIKE, ReasonCode.BRAND_IN_SUBDOMAIN,
            ReasonCode.BRAND_IN_DOMAIN, ReasonCode.USER_INFO) }
        val contextualPressure = text.categories >= 2
        val corroborated = (deception && (ReasonCode.CREDENTIAL_PATH in linkReasons || ReasonCode.PHISHING_KEYWORDS in linkReasons ||
            ReasonCode.KNOWN_PHISHING in linkReasons || text.asksForCredentials || contextualPressure)) ||
            (ReasonCode.BRAND_MISMATCH in reasons && text.asksForCredentials) ||
            (ReasonCode.KNOWN_PHISHING in linkReasons && text.asksForCredentials)
        // Formats, file extensions and model confidence cannot manufacture a red warning.
        if (!corroborated || link.trusted || text.educationalContext)
            total = minOf(total, RiskThresholds.TRUSTED_CAP)
        val meaningfulLink = linkReasons.any { it in setOf(ReasonCode.KNOWN_PHISHING, ReasonCode.USER_INFO,
            ReasonCode.LOOKALIKE, ReasonCode.BRAND_IN_SUBDOMAIN, ReasonCode.BRAND_IN_DOMAIN,
            ReasonCode.PHISHING_KEYWORDS, ReasonCode.BRAND_MISMATCH, ReasonCode.DANGEROUS_DOWNLOAD) }
        if (!meaningfulLink && !text.asksForCredentials && text.categories < 2)
            total = minOf(total, RiskThresholds.SUSPICIOUS - 1)
        return verdict(level(total), total, reasons)
    }

    private fun level(total: Int) = when {
        total >= RiskThresholds.DANGEROUS -> Level.DANGEROUS
        total >= RiskThresholds.SUSPICIOUS -> Level.SUSPICIOUS
        else -> Level.SAFE
    }

    private fun verdict(level: Level, total: Int, reasons: Set<ReasonCode>): Verdict = when (level) {
        // A SAFE result carries no warning reasons; a weak signal alone is not worth a word to the user.
        Level.SAFE -> Verdict(Level.SAFE, (total / 100.0).coerceIn(0.0, 1.0), emptySet())
        Level.SUSPICIOUS -> Verdict(level, (total / 100.0).coerceIn(0.0, 1.0), reasons)
        Level.DANGEROUS -> Verdict(level, (total / 100.0).coerceIn(0.0, 1.0), linkedSetOf(ReasonCode.LOCAL_RISK) + reasons)
    }

    companion object {
        fun load(read: (String) -> String): VerdictEngine {
            val suffixes = PublicSuffixList(read("public_suffix_list.dat"))
            return VerdictEngine(
                Allowlist(read("top_domains.txt"), suffixes), Blocklist(read("blocklist.txt")),
                UrlFeatures(suffixes), UrlModel(read("url_model.json")), TextModel(read("text_model.json"))
            )
        }
    }
}
