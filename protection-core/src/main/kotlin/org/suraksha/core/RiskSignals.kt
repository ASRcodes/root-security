package org.suraksha.core

import java.net.IDN
import java.text.Normalizer
import java.util.Locale

/**
 * Explainable, weighted local risk scoring.
 *
 * Every signal adds points and a reason the user can read. No single weak, structural
 * property (a shortener, a long path, a login page, an unusual TLD) can reach an alarm on
 * its own; an alarm needs concrete deception (lookalike or impersonated brand, a listed
 * phishing host or an @-disguised address) together with meaningful corroboration.
 * Structural properties and an executable extension never establish fraud by themselves.
 */
object RiskThresholds {
    const val SUSPICIOUS = 35
    const val DANGEROUS = 60
    /** Text without any link needs stronger evidence before it may interrupt the user. */
    const val TEXT_ONLY_DANGEROUS = 60
    const val TEXT_CAP = 50
    /** A trusted destination can never be escalated beyond a caution by message wording. */
    const val TRUSTED_CAP = DANGEROUS - 1
}

data class RiskAnalysis(val score: Int, val reasons: Set<ReasonCode>) {
    operator fun plus(other: RiskAnalysis) = RiskAnalysis(score + other.score, reasons + other.reasons)
    companion object { val NONE = RiskAnalysis(0, emptySet()) }
}

data class UrlRisk(val analysis: RiskAnalysis, val trusted: Boolean, val registrable: String)

class UrlRiskAnalyzer(private val suffixes: PublicSuffixList, private val blocklist: Blocklist) {
    companion object {
        /** Brand token -> registrable domains really owned by that brand family. */
        val BRANDS: Map<String, Set<String>> = mapOf(
            "google" to setOf("google.com", "google.co.in", "googleapis.com", "googleusercontent.com", "gstatic.com",
                "googlevideo.com", "googleblog.com", "google.dev", "withgoogle.com", "googlesource.com", "g.co", "goo.gl",
                "googletagmanager.com", "googlesyndication.com", "googleadservices.com", "forms.gle"),
            "gmail" to setOf("gmail.com", "google.com"),
            "gpay" to setOf("google.com"), "googlepay" to setOf("google.com"),
            "youtube" to setOf("youtube.com", "youtu.be", "youtubekids.com", "ytimg.com"),
            "paypal" to setOf("paypal.com", "paypal.me", "paypalobjects.com"),
            "amazon" to setOf("amazon.com", "amazon.in", "amazon.co.uk", "amazon.de", "amazonaws.com", "amazonpay.in",
                "amazon.jobs", "amzn.to", "amzn.in", "amzn.eu", "a.co", "media-amazon.com", "primevideo.com",
                "aboutamazon.com", "aboutamazon.in", "amazontrust.com", "amazon.science", "amazon.dev"),
            "flipkart" to setOf("flipkart.com", "flipkart.net", "fkrt.it", "flipkartwholesale.com"),
            "microsoft" to setOf("microsoft.com", "microsoftonline.com", "microsoft365.com", "live.com", "office.com",
                "msn.com", "azure.com", "windows.net", "outlook.com"),
            "outlook" to setOf("outlook.com", "live.com", "microsoft.com", "office.com"),
            "apple" to setOf("apple.com", "icloud.com", "apple.co", "mzstatic.com"),
            "icloud" to setOf("icloud.com", "apple.com"),
            "whatsapp" to setOf("whatsapp.com", "whatsapp.net", "wa.me"),
            "facebook" to setOf("facebook.com", "fb.com", "fb.me", "fbcdn.net", "facebook.net", "meta.com"),
            "instagram" to setOf("instagram.com", "cdninstagram.com", "instagr.am"),
            "netflix" to setOf("netflix.com", "netflix.net", "nflxvideo.net"),
            "linkedin" to setOf("linkedin.com", "lnkd.in"),
            "telegram" to setOf("telegram.org", "t.me", "telegram.me", "telegra.ph"),
            "trezor" to setOf("trezor.io"), "ledger" to setOf("ledger.com"), "metamask" to setOf("metamask.io"),
            "shopee" to setOf("shopee.com", "shopee.co.id", "shopee.com.my", "shopee.sg", "shopee.ph", "shopee.vn", "shopee.co.th", "shopee.tw"),
            "roblox" to setOf("roblox.com", "rbxcdn.com"), "steam" to setOf("steampowered.com", "steamcommunity.com"),
            "docusign" to setOf("docusign.com", "docusign.net"), "dropbox" to setOf("dropbox.com", "dropboxusercontent.com"),
            "onedrive" to setOf("live.com", "microsoft.com", "onedrive.com"), "sharepoint" to setOf("sharepoint.com", "microsoft.com"),
            "office365" to setOf("office.com", "microsoft.com"), "adobe" to setOf("adobe.com", "adobelogin.com"),
            "usps" to setOf("usps.com"), "wellsfargo" to setOf("wellsfargo.com"), "chase" to setOf("chase.com"),
            "bankofamerica" to setOf("bankofamerica.com"), "coinbase" to setOf("coinbase.com"),
            "sbi" to setOf("sbi.co.in", "onlinesbi.sbi", "sbicard.com", "sbilife.co.in", "sbimf.com", "sbisecurities.in", "sbigeneral.in"),
            "onlinesbi" to setOf("onlinesbi.sbi", "sbi.co.in"),
            "yono" to setOf("sbi.co.in", "onlinesbi.sbi"), "yonosbi" to setOf("sbi.co.in", "onlinesbi.sbi"),
            "hdfc" to setOf("hdfcbank.com", "hdfc.com", "hdfclife.com", "hdfcsec.com", "hdfcfund.com", "hdfcergo.com", "hdfcbank.net"),
            "hdfcbank" to setOf("hdfcbank.com", "hdfcbank.net"),
            "icici" to setOf("icicibank.com", "icicidirect.com", "iciciprulife.com", "icicilombard.com", "icicipruamc.com", "icicisecurities.com"),
            "icicibank" to setOf("icicibank.com"),
            "axis" to setOf("axisbank.com", "axisbank.co.in", "axisdirect.in", "axismf.com"),
            "axisbank" to setOf("axisbank.com", "axisbank.co.in"),
            "kotak" to setOf("kotak.com", "kotaksecurities.com", "kotaklife.com", "kotakmf.com", "kotak811.com"),
            "pnb" to setOf("pnbindia.in", "netpnb.com", "pnbmetlife.com"),
            "baroda" to setOf("bankofbaroda.in", "bankofbaroda.com", "bobfinancial.com", "bobcard.co.in"),
            "bankofbaroda" to setOf("bankofbaroda.in", "bankofbaroda.com"),
            "unionbank" to setOf("unionbankofindia.co.in", "unionbankofindia.com"),
            "canarabank" to setOf("canarabank.com", "canarabank.in"),
            "paytm" to setOf("paytm.com", "paytm.in", "paytmbank.com", "paytmmall.com", "paytmmoney.com", "paytm.me"),
            "phonepe" to setOf("phonepe.com"),
            "bhim" to setOf("bhimupi.org.in", "npci.org.in"),
            "npci" to setOf("npci.org.in"),
            "airtel" to setOf("airtel.in", "airtel.com", "airtelxstream.in", "airtelpaymentsbank.com", "airtelthanks.in"),
            "jio" to setOf("jio.com", "jiomart.com", "jiocinema.com", "jiosaavn.com", "jiohotstar.com", "ril.com", "jiofiber.com"),
            "vodafone" to setOf("vodafone.in", "myvi.in", "vodafone.com", "vodafoneidea.com"),
            "bsnl" to setOf("bsnl.co.in", "bsnl.in"),
            "irctc" to setOf("irctc.co.in", "irctc.com", "irctctourism.com"),
            "aadhaar" to emptySet(), "aadhar" to emptySet(), "uidai" to emptySet(), "epfo" to emptySet(),
            "incometax" to emptySet(), "indiapost" to emptySet(), "rbi" to setOf("rbi.org.in"),
            "lic" to setOf("licindia.in", "licindia.com"),
            "fedex" to setOf("fedex.com"), "dhl" to setOf("dhl.com", "dhl.de", "dhl.co.in"),
            "bluedart" to setOf("bluedart.com"),
            "binance" to setOf("binance.com"),
            "hotstar" to setOf("hotstar.com", "jiohotstar.com"),
        )

        /** Brand tokens this short or this generic must appear as a whole token, never as a substring. */
        private const val MIN_SUBSTRING_BRAND = 6
        /** Brands one letter away from an ordinary word ("binance"/"finance") only match exact disguises. */
        private val EXACT_ONLY = setOf("binance", "coinbase", "hotstar", "icloud", "ledger", "steam", "chase", "adobe")
        /** Ordinary words that are a single edit from a brand name. */
        private val REAL_WORDS = setOf("finance", "googly", "paypay", "applet", "amazona", "outlooks")

        /** Popular, well-run consumer destinations. Only wording can raise them, and only to a caution. */
        val TRUSTED: Set<String> = BRANDS.values.flatten().toSet() - setOf(
            // These host arbitrary customer content: the brand is real but the page owner is not.
            "amazonaws.com", "googleusercontent.com", "windows.net", "g.co", "goo.gl"
        ) + setOf(
            "wikipedia.org", "wikimedia.org", "github.com", "githubusercontent.com", "stackoverflow.com", "reddit.com",
            "x.com", "twitter.com", "zoom.us", "python.org", "android.com", "kotlinlang.org", "mozilla.org", "medium.com",
            "quora.com", "bbc.com", "bbc.co.uk", "cnn.com", "nytimes.com", "ndtv.com", "thehindu.com",
            "timesofindia.com", "indiatimes.com", "hindustantimes.com", "indianexpress.com", "myntra.com",
            "swiggy.com", "zomato.com", "nykaa.com", "ajio.com", "meesho.com", "bigbasket.com", "makemytrip.com",
            "goibibo.com", "olacabs.com", "uber.com", "spotify.com", "openai.com", "chatgpt.com", "anthropic.com",
            "claude.ai", "kaggle.com", "coursera.org", "udemy.com", "geeksforgeeks.org", "w3schools.com",
            "leetcode.com", "hackerrank.com", "cricbuzz.com", "espncricinfo.com", "yahoo.com", "bing.com",
            "duckduckgo.com", "scikit-learn.org", "mygov.in", "india.gov.in", "digilocker.gov.in", "naukri.com",
            "zerodha.com", "groww.in", "upstox.com", "swiggy.in", "cred.club", "freecharge.in", "mobikwik.com",
            "airbnb.com", "booking.com", "imdb.com", "canva.com", "notion.so", "slack.com", "discord.com",
            "dropbox.com", "adobe.com", "figma.com", "vercel.com", "netlify.com", "cloudflare.com", "jetbrains.com",
        )

        /** Registries that ordinary people cannot register in. */
        private val TRUSTED_SUFFIXES = listOf(".gov.in", ".nic.in", ".gov", ".mil", ".ac.in", ".edu", ".bank.in", ".sbi")

        val SHORTENERS = setOf("bit.ly", "bitly.com", "t.ly", "tinyurl.com", "is.gd", "v.gd", "cutt.ly", "rb.gy",
            "shorturl.at", "ow.ly", "rebrand.ly", "s.id", "tiny.cc", "t2m.io", "short.gy", "shorte.st", "adf.ly",
            "bl.ink", "clck.ru", "qrco.de", "tiny.one", "surl.li", "u.to", "urlz.fr", "lnk.bio", "linktr.ee")

        /** Free website builders and tunnels: anyone can publish a page under them in minutes. */
        val FREE_HOSTS = setOf("web.app", "firebaseapp.com", "000webhostapp.com", "repl.co", "replit.app", "replit.dev",
            "glitch.me", "netlify.app", "vercel.app", "pages.dev", "workers.dev", "github.io", "gitlab.io",
            "godaddysites.com", "weebly.com", "wixsite.com", "square.site", "blogspot.com", "ngrok.io", "ngrok-free.app",
            "ngrok.app", "herokuapp.com", "onrender.com", "trycloudflare.com", "duckdns.org", "appspot.com",
            "webflow.io", "framer.website", "carrd.co", "mystrikingly.com", "jimdosite.com", "sites.google.com",
            "s3.amazonaws.com", "storage.googleapis.com", "firebasestorage.googleapis.com", "ipfs.io", "dweb.link",
            "r2.dev", "surge.sh", "16mb.com", "freenom.com", "byethost.com", "infinityfreeapp.com", "wcomhost.com")

        val RISKY_TLDS = setOf("xyz", "top", "click", "work", "zip", "mov", "cam", "buzz", "icu", "loan", "rest",
            "support", "online", "site", "live", "tk", "ml", "ga", "cf", "gq", "cyou", "sbs", "cfd", "bond",
            "quest", "monster", "lol", "shop", "info", "vip", "win", "bid", "party", "review", "country", "stream",
            "download", "racing", "date", "faith", "men", "gdn", "kim", "fit", "host", "pw", "cc", "ws", "su", "store",
            "fun", "space", "website", "today", "life", "club", "link", "help", "claims", "loans", "finance")

        /** Words a fraudster puts into a hostname to look official or urgent. */
        private val HOST_KEYWORDS = setOf("kyc", "ekyc", "verify", "verification", "verified", "update", "updation",
            "secure", "security", "login", "signin", "logon", "account", "accounts", "banking", "netbanking", "wallet",
            "reward", "rewards", "bonus", "prize", "gift", "gifts", "claim", "refund", "unblock", "blocked",
            "suspend", "suspended", "alert", "helpdesk", "customercare", "offer", "offers", "lucky", "winner",
            "lottery", "cashback", "free", "pan", "aadhaar", "aadhar", "otp", "payment", "redeem", "voucher",
            "points", "challan", "electricity", "kisan", "subsidy", "loan", "apk", "mod", "reactivate", "validate",
            "confirm", "recover", "recovery", "auth", "authentication", "support", "service", "official")
        private val SUBSTRING_HOST_KEYWORDS = listOf("kyc", "verify", "verification", "unblock", "netbanking", "reward",
            "lottery", "cashback", "aadhaar", "aadhar", "signin", "login", "refund", "giftcard", "challan")

        private val PATH_KEYWORDS = Regex("(?i)(log-?in|sign-?in|verify|verification|kyc|password|passwd|otp|" +
            "account[-_/]?update|secure|banking|update[-_/]?pan|aadhaa?r|refund|reward|claim|prize|bonus|unblock|" +
            "suspend|wallet|confirm|billing|redeem|webscr|cmd=_|auth|recover|reset)")
        private val DANGEROUS_DOWNLOAD = Regex("(?i)\\.(apk|xapk|apks|apkm|dex)$")
        private val DESKTOP_DOWNLOAD = Regex("(?i)\\.(exe|msi|scr|bat|cmd|vbs|jar|ps1|hta)$")

        private val SKELETON = mapOf(
            'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'х' to 'x', 'у' to 'y', 'і' to 'i',
            'ј' to 'j', 'ӏ' to 'l', 'ԁ' to 'd', 'ɡ' to 'g', 'ո' to 'n', 'ս' to 'u', 'ν' to 'v', 'ο' to 'o',
            '0' to 'o', '1' to 'l', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '8' to 'b', '9' to 'g'
        )

        internal fun skeleton(token: String): String =
            token.map { SKELETON[it] ?: it }.joinToString("").replace("rn", "m").replace("vv", "w")

        internal fun oneEditAway(a: String, b: String): Boolean {
            if (kotlin.math.abs(a.length - b.length) > 1 || a == b) return false
            var i = 0; var j = 0; var edits = 0
            while (i < a.length && j < b.length) {
                if (a[i] == b[j]) { i++; j++; continue }
                if (++edits > 1) return false
                when {
                    a.length > b.length -> i++
                    b.length > a.length -> j++
                    else -> { i++; j++ }
                }
            }
            return edits + (a.length - i) + (b.length - j) == 1
        }

        private fun privateIp(host: String): Boolean {
            if (host.contains(':')) {
                val h = host.lowercase(Locale.ROOT)
                return h == "::1" || h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") ||
                    h.startsWith("feb") || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("2001:db8")
            }
            val parts = host.split('.').mapNotNull(String::toIntOrNull)
            if (parts.size != 4) return false
            return parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) ||
                (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 169 && parts[1] == 254)
        }
    }

    fun isTrusted(host: String, registrable: String?): Boolean =
        (registrable != null && registrable in TRUSTED) || TRUSTED_SUFFIXES.any { host.endsWith(it) }

    fun analyze(url: ParsedUrl): UrlRisk {
        val host = url.host
        val registrable = suffixes.registrableDomain(host)
        if (isTrusted(host, registrable)) {
            // A user-info prefix can disguise even a familiar destination.
            val disguise = if (url.userInfo) RiskAnalysis(50, setOf(ReasonCode.USER_INFO)) else RiskAnalysis.NONE
            return UrlRisk(disguise, disguise.score == 0, registrable.orEmpty())
        }
        var score = 0
        val reasons = linkedSetOf<ReasonCode>()
        fun add(points: Int, reason: ReasonCode) { score += points; reasons += reason }

        // The bundled list is historical and includes platforms that once hosted a phishing page
        // (file lockers, note apps). It corroborates other evidence but cannot alarm alone.
        val platform = host in SHORTENERS || registrable in SHORTENERS || registrable in FREE_HOSTS || host in FREE_HOSTS
        if (!platform && blocklist.contains(host)) add(35, ReasonCode.KNOWN_PHISHING)
        if (url.userInfo) add(50, ReasonCode.USER_INFO)
        if (url.ip) { if (!privateIp(host)) add(40, ReasonCode.IP_HOST) }

        val unicodeHost = runCatching { IDN.toUnicode(host).lowercase(Locale.ROOT) }.getOrDefault(host)
        val domain = registrable.orEmpty()
        val label = if (domain.isEmpty()) "" else runCatching {
            IDN.toUnicode(domain.substringBefore('.')).lowercase(Locale.ROOT)
        }.getOrDefault(domain.substringBefore('.'))
        val subdomain = if (domain.isEmpty()) "" else unicodeHost.removeSuffix(
            runCatching { IDN.toUnicode(domain).lowercase(Locale.ROOT) }.getOrDefault(domain)).trimEnd('.')
        val labelTokens = label.split('-', '_').filter(String::isNotEmpty)
        val subTokens = subdomain.split('.', '-', '_').filter(String::isNotEmpty)

        // Brand impersonation: the strongest local evidence of phishing.
        var brandInHost = false
        if (!url.ip && domain.isNotEmpty()) {
            var lookalike = false
            var disguise = false
            var inSubdomain = false
            var officialInSubdomain = false
            var inDomain = false
            val candidates = (labelTokens + label.replace("-", "")).filter { it !in REAL_WORDS }
            val tld = host.substringAfterLast('.')
            for ((brand, official) in BRANDS) {
                if (domain in official) continue
                // google.com.hk, amazon.com.br: the brand's own label on a country domain.
                if (tld !in RISKY_TLDS && official.any { label == it.substringBefore('.') }) continue
                // "paypal.com.account-check.example", "amazon.in-orders.example": the real address as camouflage.
                if (subdomain.isNotEmpty() && official.any { o ->
                        Regex("(^|[.-])${Regex.escape(o)}[.-]").find(unicodeHost)?.let { it.range.first < subdomain.length } == true
                    }) officialInSubdomain = true
                if (subTokens.any { it == brand }) inSubdomain = true
                if (labelTokens.any { it == brand } && (labelTokens.size > 1 ||
                        url.host.substringAfterLast('.') in RISKY_TLDS || FREE_HOSTS.any { host.endsWith(".$it") })) inDomain = true
                if (brand.length >= MIN_SUBSTRING_BRAND && candidates.any { it != brand && it.contains(brand) }) inDomain = true
                if (brand.length >= 5 && candidates.any { token -> token != brand && skeleton(token) == brand }) disguise = true
                if (brand.length >= 5 && candidates.any { token ->
                        token != brand && !token.contains(brand) && skeleton(token).let { sk ->
                            sk == brand || (brand.length >= MIN_SUBSTRING_BRAND && sk.length >= MIN_SUBSTRING_BRAND &&
                                ((brand !in EXACT_ONLY && oneEditAway(sk, brand)) || sk.contains(brand)))
                        }
                    }) lookalike = true
            }
            // Digit/homoglyph swaps (g00gle, аpple) are deliberate; a one-letter typo can be coincidence.
            if (disguise) add(60, ReasonCode.LOOKALIKE) else if (lookalike) add(45, ReasonCode.LOOKALIKE)
            if (officialInSubdomain) add(55, ReasonCode.BRAND_IN_SUBDOMAIN)
            else if (inSubdomain) add(40, ReasonCode.BRAND_IN_SUBDOMAIN)
            if (inDomain && !lookalike) add(30, ReasonCode.BRAND_IN_DOMAIN)
            brandInHost = lookalike || inSubdomain || officialInSubdomain || inDomain
        }

        if (host.split('.').any { it.startsWith("xn--") } && ReasonCode.LOOKALIKE !in reasons) add(20, ReasonCode.PUNYCODE)
        if (!url.ip && host.substringAfterLast('.') in RISKY_TLDS) add(20, ReasonCode.RISKY_TLD)

        val hostKeywordTokens = (labelTokens + subTokens).filter { it in HOST_KEYWORDS }.toMutableSet()
        SUBSTRING_HOST_KEYWORDS.filter { keyword -> labelTokens.any { it != keyword && it.contains(keyword) } }
            .forEach { hostKeywordTokens += it }
        when {
            hostKeywordTokens.size >= 2 -> add(25, ReasonCode.PHISHING_KEYWORDS)
            hostKeywordTokens.size == 1 -> add(15, ReasonCode.PHISHING_KEYWORDS)
        }
        // A brand plus an action word ("netflix-payment", "sbi-kyc") is the classic phishing hostname.
        if (brandInHost && hostKeywordTokens.isNotEmpty()) score += 15

        if (host in SHORTENERS || domain in SHORTENERS) add(15, ReasonCode.SHORTENER)
        val freeHost = FREE_HOSTS.any { host == it || host.endsWith(".$it") }
        if (freeHost) add(15, ReasonCode.FREE_HOSTING)
        // A brand page on a free site builder is never the brand's real login page.
        if (freeHost && brandInHost) score += 15
        // "2r9p1e-1v8m9p2a-8k1zp9m2s4.pages.dev": machine-generated throwaway names.
        val nameTokens = (labelTokens + subTokens).filter { it != "www" }
        val mixed = nameTokens.count { t -> t.any(Char::isDigit) && t.any(Char::isLetter) && t.length >= 4 }
        val vowelless = nameTokens.count { t -> t.length >= 4 && t.all(Char::isLetter) && t.none { it in "aeiouy" } }
        if (!url.ip && (mixed >= 2 || mixed + vowelless >= 3)) add(if (freeHost) 20 else 10, ReasonCode.RANDOM_HOSTNAME)
        if (unicodeHost.count { it == '-' } >= 3) add(10, ReasonCode.MANY_HYPHENS)
        if (url.port >= 0 && url.port != 80 && url.port != 443) add(10, ReasonCode.NONSTANDARD_PORT)
        val depth = if (domain.isEmpty()) 0 else host.split('.').size - domain.split('.').size
        if (url.raw.length > 200 || host.length > 60 || depth >= 4) add(10, ReasonCode.LONG_URL)

        val path = runCatching { java.net.URLDecoder.decode(url.path, "UTF-8") }.getOrDefault(url.path)
        when {
            DANGEROUS_DOWNLOAD.containsMatchIn(path) -> add(35, ReasonCode.DANGEROUS_DOWNLOAD)
            DESKTOP_DOWNLOAD.containsMatchIn(path) -> add(35, ReasonCode.DANGEROUS_DOWNLOAD)
        }
        val credentialPath = PATH_KEYWORDS.containsMatchIn(url.path + "?" + url.query)
        if (credentialPath) add(10, ReasonCode.CREDENTIAL_PATH)
        // "45.77.12.9/sbi/login.php": a bank's sign-in page served from somebody else's server.
        if (credentialPath && !brandInHost && BRANDS.any { (brand, official) -> domain !in official &&
                Regex("(?i)(^|[^a-z])${Regex.escape(brand)}([^a-z]|$)").containsMatchIn(path) }) add(15, ReasonCode.BRAND_MISMATCH)

        val meaningful = reasons.any { it in setOf(ReasonCode.KNOWN_PHISHING, ReasonCode.USER_INFO,
            ReasonCode.LOOKALIKE, ReasonCode.BRAND_IN_SUBDOMAIN, ReasonCode.BRAND_IN_DOMAIN,
            ReasonCode.PHISHING_KEYWORDS, ReasonCode.BRAND_MISMATCH, ReasonCode.DANGEROUS_DOWNLOAD) }
        if (!meaningful) score = minOf(score, RiskThresholds.SUSPICIOUS - 1)
        return UrlRisk(RiskAnalysis(score, reasons), false, domain)
    }
}

/** Rule-based scam wording in English, Hindi, Punjabi and Hinglish, plus the trained text model. */
object ScamLanguage {
    private fun rx(pattern: String) = Regex(pattern, setOf(RegexOption.IGNORE_CASE))

    private val credentials = rx("\\b(otp|one[- ]?time[- ]?password|upi[- ]?pin|m-?pin|atm[- ]?pin|cvv|password|" +
        "card (number|details)|verification code|security code|net ?banking (id|details|password))\\b|" +
        "\\bshare (your |the )?(pin|code)\\b|ओटीपी|पासवर्ड|पिन|ਓਟੀਪੀ|ਪਾਸਵਰਡ|ਪਿੰਨ")
    private val credentialSafety = rx("\\b(do not|don'?t|never|not to) (share|disclose|tell)|" +
        "(किसी के साथ|किसी को) (भी )?(साझा|शेयर) न|ਕਿਸੇ ਨਾਲ (ਵੀ )?(ਸਾਂਝਾ|ਸ਼ੇਅਰ) ਨਾ")
    private val credentialAction = rx("\\b(share|send|tell|provide|enter|submit|reply|give|type|forward)\\b|" +
        "साझा करें|शेयर करें|भेजें|बताएं|ਦੱਸੋ|ਭੇਜੋ|ਸ਼ੇਅਰ ਕਰੋ")
    private val educationalFraming = rx("\\b(awareness|training|scam example|phishing example|" +
        "example of (a |an )?(scam|phishing)|scammers? (say|ask)|beware of|reported (a |this )?scam)\\b|" +
        "जागरूकता|धोखाधड़ी का उदाहरण|ਜਾਗਰੂਕਤਾ|ਧੋਖਾਧੜੀ ਦੀ ਉਦਾਹਰਨ")
    private val quotedExample = Regex("\"[^\"]{1,3000}\"|“[^”]{1,3000}”|‘[^’]{1,3000}’")
    private val urgency = rx("\\b(urgent(ly)?|immediately|right now|asap|within \\d+ ?(hours?|hrs?|minutes?|mins?)|" +
        "last (chance|day|warning|reminder)|(kyc|account|a/c|card|sim|pan|subscription|policy|points|rewards?)\\b.{0,25}" +
        "expir(e|es|ed|ing)|will be (blocked|suspended|deactivated|closed|" +
        "disconnected|cancell?ed|terminated)|(account|a/c|card|sim|number|kyc|wallet|connection)\\b.{0,25}" +
        "(blocked|suspended|deactivated|closed|expired|on hold|frozen)|disconnect(ed|ion)?|legal action|" +
        "arrest(ed)?|police|cbi|cyber ?crime|court|penalty|tonight at|by today|turant|jaldi|band ho)\\b|" +
        "तुरंत|आज रात|ब्लॉक|बंद कर|निलंबित|गिरफ्तार|ਤੁਰੰਤ|ਅੱਜ ਰਾਤ|ਬੰਦ ਕਰ|ਬਲੌਕ")
    private val account = rx("\\b(e-?kyc|kyc|pan( card)?|aadhaa?r|update your (account|details|kyc|pan)|" +
        "verify your (account|identity|details|number)|account (update|verification|validation)|re-?activate)\\b|" +
        "केवाईसी|आधार|पैन|ਕੇਵਾਈਸੀ|ਆਧਾਰ|ਪੈਨ")
    private val reward = rx("\\b(congratulations|congrats|you('ve| have)? won|winner|lottery|lucky draw|jackpot|" +
        "prize|reward points?|cash ?back|free (gift|credits?|recharge|iphone|data|tickets?)|you are selected|" +
        "selected for|claim (your|now|it)|bonus|inaam|jeeta|win (cash|money|a prize|an? (iphone|car|gift))|chances? to win)\\b|इनाम|जीत|लॉटरी|ਇਨਾਮ|ਬੋਨਸ|ਜਿੱਤ")
    private val payment = rx("\\b((processing|delivery|registration|activation|release|clearance|courier) " +
        "(fee|charges?)|pay (rs\\.?|₹|inr) ?\\d|send (money|rs|₹)|refund (process|pending)|bhej(o|ein|en)|" +
        "(electricity|power|bijli) (bill|connection))\\b|फीस|शुल्क|ਫੀਸ")
    private val remoteAccess = rx("\\b(any ?desk|team ?viewer|quick ?support|rust ?desk|airdroid|" +
        "screen ?shar(e|ing)|remote access)\\b")
    private val apk = rx("\\.apk\\b|\\bapk (file|download)\\b|install (this|the) apk")

    /** [analysis] is capped for combination with a link; [raw] is used when the message has no link. */
    data class Text(val analysis: RiskAnalysis, val raw: Int, val categories: Int,
                    val asksForCredentials: Boolean, val educationalContext: Boolean = false)

    private val links = Regex("""(?i)https?://\S+|www\.\S+|(?<![\p{L}\p{N}_@])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}(?::[0-9]{1,5})?(?:[/?#]\S*)?""")
    /** Wording is judged without the link itself; the link has its own analysis. */
    fun withoutLinks(message: String): String =
        links.replace(Normalizer.normalize(message, Normalizer.Form.NFKC).take(4096), " ")

    fun analyze(message: String, modelScore: Double): Text {
        // Detect quote boundaries before stripping links, since a URL can consume its closing quote.
        val normalized = Normalizer.normalize(message, Normalizer.Form.NFKC).take(4096)
        val educational = educationalFraming.containsMatchIn(normalized) && quotedExample.containsMatchIn(normalized)
        val text = withoutLinks(if (educational) quotedExample.replace(normalized, " ") else normalized)
        var score = 0
        var categories = 0
        val reasons = linkedSetOf<ReasonCode>()
        val credentialHit = credentials.containsMatchIn(text) && credentialAction.containsMatchIn(text) &&
            !credentialSafety.containsMatchIn(text)
        if (credentialHit) { score += 20; categories++; reasons += ReasonCode.CREDENTIAL_REQUEST }
        listOf(urgency, account, reward, payment).forEach {
            if (it.containsMatchIn(text)) { score += 15; categories++; reasons += ReasonCode.SCAM_LANGUAGE }
        }
        val remote = remoteAccess.containsMatchIn(text)
        if (remote) { score += 35; categories++; reasons += ReasonCode.REMOTE_ACCESS_REQUEST }
        if (apk.containsMatchIn(text)) { score += 25; categories++; reasons += ReasonCode.DANGEROUS_DOWNLOAD }
        // The trained model corroborates rules; it never fires alone on neutral sharing language.
        if (categories > 0) when {
            modelScore >= 0.95 -> { score += 20; reasons += ReasonCode.SCAM_LANGUAGE }
            modelScore >= 0.9 -> { score += 15; reasons += ReasonCode.SCAM_LANGUAGE }
            modelScore >= 0.8 -> { score += 10; reasons += ReasonCode.SCAM_LANGUAGE }
        } else if (modelScore >= 0.97) { score += 15; reasons += ReasonCode.SCAM_LANGUAGE }
        if (categories >= 3) score += 10
        if (categories < 2 && !credentialHit && !remote) score = minOf(score, RiskThresholds.SUSPICIOUS - 1)
        return Text(RiskAnalysis(minOf(score, RiskThresholds.TEXT_CAP), reasons), score, categories,
            credentialHit || remote, educational)
    }

    private val brandWords: List<Pair<String, Regex>> = UrlRiskAnalyzer.BRANDS.keys
        .filter { it.length >= 3 }
        .map { it to Regex("(?i)(?<![a-z0-9])${Regex.escape(it)}(?![a-z0-9])") } +
        listOf("sbi" to Regex("(?i)state bank|एसबीआई|ਐਸਬੀਆਈ"), "hdfc" to Regex("(?i)एचडीएफसी|ਐਚਡੀਐਫਸੀ"),
            "axis" to Regex("(?i)एक्सिस|ਐਕਸਿਸ"), "baroda" to Regex("(?i)बड़ौदा|ਬੜੌਦਾ"),
            "icici" to Regex("(?i)आईसीआईसीआई"), "amazon" to Regex("(?i)अमेज़न|ਐਮਾਜ਼ਾਨ"))

    /** Brands named in the message whose official domains do not include the linked site. */
    fun mentionedBrands(message: String): Set<String> = withoutLinks(message).let { text ->
        brandWords.filter { (_, pattern) -> pattern.containsMatchIn(text) }.mapTo(linkedSetOf()) { it.first }
    }
}
