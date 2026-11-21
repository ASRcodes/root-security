package org.suraksha.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Labeled demo corpus. Ordinary links people really share must stay silent; scam links and
 * messages must raise the full-screen alarm. URLs are only parsed, never visited.
 */
class ScamDetectionTest {
    companion object {
        private fun resource(name: String) = requireNotNull(
            ScamDetectionTest::class.java.classLoader.getResourceAsStream(name)
        ) { "Required exported asset missing: $name" }.bufferedReader().use { it.readText() }

        private val engine by lazy { VerdictEngine.load(::resource) }

        /** Messages exactly as they would arrive by SMS/WhatsApp. Each must produce no warning. */
        val GENUINE = listOf(
            "https://www.google.com",
            "https://www.google.com/search?q=weather+delhi",
            "Hi, check this video https://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s",
            "https://github.com/ASRcodes/DontMissMoM",
            "https://github.com/user/repo/releases/download/v1.0/app-release.apk",
            "https://en.wikipedia.org/wiki/Phishing",
            "https://www.amazon.in/dp/B0CHX1W1XY?ref=ppx_yo2ov_dt_b_product_details",
            "Order delivered! Rate us https://amzn.in/d/3xYzAbC",
            "https://www.flipkart.com/apple-iphone-15/p/itm6ac6485515ae4",
            "https://www.paypal.com/signin",
            "https://www.onlinesbi.sbi/",
            "https://retail.onlinesbi.sbi/retail/login.htm",
            "https://www.sbi.co.in/web/personal-banking",
            "https://netbanking.hdfcbank.com/netbanking/",
            "https://www.icicibank.com/personal-banking/cards",
            "https://www.irctc.co.in/nget/train-search",
            "https://uidai.gov.in/en/my-aadhaar/update-aadhaar.html",
            "https://www.incometax.gov.in/iec/foportal/",
            "Join the meeting https://meet.google.com/abc-defg-hij",
            "https://zoom.us/j/1234567890?pwd=abcDEF123",
            "https://docs.google.com/document/d/1AbCdEf/edit?usp=sharing",
            "https://drive.google.com/file/d/1AbCdEf/view",
            "https://wa.me/919876543210",
            "https://www.instagram.com/p/C1a2b3c4d5/",
            "https://www.linkedin.com/in/someone",
            "https://stackoverflow.com/questions/123/how-to-parse-url",
            "https://developer.android.com/studio/run/device",
            "https://docs.python.org/3/library/urllib.parse.html",
            "https://www.airtel.in/prepaid-recharge/",
            "i.airtel.in/rc349sil3",
            "https://weurl.co/GDMNKS/oo8a70",
            "https://www.goodmonk.in/account/login",
            "https://www.goodmonk.in/?utm_source=whatsapp&utm_medium=share",
            "https://www.myntra.com/shoes",
            "https://www.zomato.com/ncr/restaurants",
            "https://www.swiggy.com/",
            "https://bit.ly/family-meetup",
            "Here are the photos https://photos.app.goo.gl/AbCdEf123",
            "https://medium.com/@someone/my-first-post-123abc",
            "https://www.bbc.com/news/world-12345678",
            "https://timesofindia.indiatimes.com/india/article/12345.cms",
            "https://chatgpt.com/share/abc-123",
            "https://my-family-photo-album.netlify.app/",
            "http://192.168.1.1/login",
            "https://www.jio.com/selfcare/recharge/mobility/",
            "https://openai.com/index/hello",
            "Your Airtel recharge of Rs 299 is successful. View details at https://www.airtel.in/",
            "123456 is your OTP for Amazon login. Do not share it with anyone.",
            "Hi Mom, here is the link you asked for https://www.bigbasket.com/pc/fruits-vegetables/",
            "Get 20% off on your first order. Shop now https://www.ajio.com/sale",
            "Your parcel is out for delivery today. Track: https://www.bluedart.com/tracking",
            "See you at 6? Address: https://maps.app.goo.gl/xyz123",
            "https://scikit-learn.org/stable/",
            "https://www.kaggle.com/datasets",
            "https://ordinary.xyz/account/login",
            "https://blog.example.com/2024/05/my-post",
            "https://shop.mybrand.store/checkout/payment",
        )

        /** Corroborated local risk examples; all fabricated destinations are reserved .example. */
        val SCAMS = listOf(
            DemoExamples.HIGH_RISK_MESSAGE,
            "https://sbi-kyc-update.example/login",
            "https://paypa1.example/verify",
            "https://paypal.com.account-verify.example/signin",
            "https://youtube.com.evil.example/verify",
            "https://amaz0n-rewards.example/claim",
            "Congratulations! You won Rs 50,000 Amazon gift. Claim now https://amazon-gift-claim.example/redeem",
            "https://paytm-cashback-offer.example/redeem",
            "https://yono-secure.example/login",
            "Your HDFC account is blocked. Verify PAN now https://hdfc-pan-verify.example/login",
            "https://hdfcbank.secure-login.example/netbanking",
            "https://faceb00k-login.example/",
            "https://instagram-verify-badge.example/form",
            "https://g00gle.example/account/recovery",
            "https://netflix-payment-update.example/billing",
            "https://flipkart-bigbillion-lucky-winner.example/",
            "https://trusted.example@phish.example/verify",
            "Your SBI account will be blocked. Share your OTP immediately: https://sbi-verify.example/login",
            "Income tax refund approved. Submit details: https://incometax-refund.example/claim",
            "https://whatsapp-security-verify.example/",
            "https://sbi.verify-kyc.example/account-update",
            "https://аpple.example/id/verify"
        )

        /** Text-only scams (no link) — interrupt only when they ask for credentials or remote access. */
        val TEXT_SCAMS = listOf(
            "Your account will be blocked. Share your OTP immediately to verify your account",
            "This is bank officer. Your KYC is expired and card will be blocked today. Tell me the OTP you received now.",
            "Sir install AnyDesk app urgently and share the 9 digit code, your refund will be processed immediately",
        )
    }

    private fun worst(message: String): Verdict {
        val results = MessagePipeline(engine).scan(message, "test", deduplicate = false)
        return results.maxByOrNull { it.verdict.level.ordinal }?.verdict ?: Verdict(Level.SAFE, 0.0, emptySet())
    }

    @Test fun printScoresForReview() {
        val report = buildString {
            (GENUINE.map { "genuine" to it } + SCAMS.map { "scam" to it } + TEXT_SCAMS.map { "text" to it }).forEach { (kind, message) ->
                val v = worst(message)
                appendLine("%-7s %-10s %3d %s  <- %s".format(kind, v.level, (v.score * 100).toInt(),
                    v.reasonCodes.joinToString(","), message.take(90)))
            }
        }
        File("build/reports/scam-detection.txt").apply { parentFile.mkdirs(); writeText(report) }
        println(report)
    }

    @Test fun genuineLinksStaySilent() {
        val failures = GENUINE.filter { worst(it).level != Level.SAFE }.map { "$it -> ${worst(it)}" }
        assertTrue("Genuine links raised a warning:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun scamLinksRaiseTheAlarm() {
        val failures = SCAMS.filter { worst(it).level != Level.DANGEROUS }.map { "$it -> ${worst(it)}" }
        assertTrue("Scam links missed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun textOnlyScamsRaiseTheAlarm() {
        val failures = TEXT_SCAMS.filter { worst(it).level != Level.DANGEROUS }.map { "$it -> ${worst(it)}" }
        assertTrue("Text scams missed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun dangerousLocalVerdictsSurviveReputationExpiry() {
        val verdict = worst(SCAMS.first())
        assertTrue(ReasonCode.LOCAL_RISK in verdict.reasonCodes)
        val result = ScanResult("sbi-verify.example", ReputationPolicy.notChecked(verdict), 100L)
        assertEquals(Level.DANGEROUS, ReputationPolicy.expire(result, 100L + ReputationPolicy.MAX_AGE_MILLIS * 4).verdict.level)
    }

    @Test fun trustedDestinationsAreCappedAtCaution() {
        val message = "Your account will be blocked. Share your OTP immediately to verify your account https://www.google.com/"
        assertNotEquals(Level.DANGEROUS, engine.evaluate("https://www.google.com/", message).level)
    }

    @Test fun everyDangerousVerdictExplainsItself() {
        SCAMS.forEach { message ->
            val reasons = worst(message).reasonCodes - ReasonCode.LOCAL_RISK
            assertTrue("No concrete reason for $message", reasons.isNotEmpty())
            reasons.forEach { assertTrue(ReasonMessages.get(it, "en").isNotBlank()) }
        }
    }
}
