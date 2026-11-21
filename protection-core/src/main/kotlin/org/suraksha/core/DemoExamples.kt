package org.suraksha.core

/** Shared, inert inputs for the real scanner. None of these constants supplies a verdict. */
object DemoExamples {
    const val NORMAL_URL = "https://www.wikipedia.org/"
    const val AIRTEL_URL = "i.airtel.in/rc349sil3"
    const val GOOD_MONK_URL = "https://weurl.co/GDMNKS/oo8a70"
    const val SUSPICIOUS_URL = "https://account-support.example/login"
    const val HIGH_RISK_URL = "https://sbi-verify.example/login"
    const val HIGH_RISK_MESSAGE = "Your SBI account will be blocked today. Update KYC immediately: https://sbi-verify.example/login"
    const val GOOGLE_TEST_URL = "https://testsafebrowsing.appspot.com/s/phishing.html"

    fun isReservedExampleUrl(url: String): Boolean = ExampleLinks.isReservedExample(url)
    fun markTestInput(url: String, verdict: Verdict): Verdict = ExampleLinks.markTestInput(url, verdict)
}

/** Reserved .example addresses are labelled after ordinary classification, in every input route. */
object ExampleLinks {
    fun isReservedExample(url: String): Boolean {
        val host = UrlParser.parse(url)?.host?.trimEnd('.') ?: return false
        return host.equals("example", ignoreCase = true) || host.endsWith(".example", ignoreCase = true)
    }

    fun markTestInput(url: String, verdict: Verdict): Verdict =
        if (isReservedExample(url)) verdict.copy(reasonCodes = verdict.reasonCodes + ReasonCode.TEST_INPUT) else verdict
}
