package org.suraksha.core

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SafeBrowsingUrlsTest {
    private val suffixes = PublicSuffixList("com\norg\nnet\nin\nco\nco.uk\nde\nio\nappspot.com\ngithub.io\n*.ck\n!www.ck")
    private val urls = SafeBrowsingUrls(suffixes)

    @Test fun canonicalizesOfficialV4VectorsUsingV5Expressions() {
        // Clear vectors from https://developers.google.com/safe-browsing/v4/urls-hashing.
        // Host/path normalization is shared; host suffix generation follows the newer v5 PSL rule.
        val vectors = mapOf(
            "http://host/%25%32%35" to "host/%25",
            "http://host/%25%32%35%25%32%35" to "host/%25%25",
            "http://host/%2525252525252525" to "host/%25",
            "http://host/asdf%25%32%35asd" to "host/asdf%25asd",
            "http://host/%%%25%32%35asd%%" to "host/%25%25%25asd%25%25",
            "http://www.google.com/" to "www.google.com/",
            "http://%31%36%38%2e%31%38%38%2e%39%39%2e%32%36/%2E%73%65%63%75%72%65/%77%77%77%2E%65%62%61%79%2E%63%6F%6D/" to "168.188.99.26/.secure/www.ebay.com/",
            "http://3279880203/blah" to "195.127.0.11/blah",
            "http://www.google.com/blah/.." to "www.google.com/",
            "www.google.com/" to "www.google.com/",
            "www.google.com" to "www.google.com/",
            "http://www.evil.com/blah#frag" to "www.evil.com/blah",
            "http://www.GOOgle.com/" to "www.google.com/",
            "http://www.google.com.../" to "www.google.com/",
            "http://www.google.com/q?" to "www.google.com/q?",
            "http://www.google.com/q?r?" to "www.google.com/q?r?",
            "http://www.google.com/q?r?s" to "www.google.com/q?r?s",
            "http://evil.com/foo#bar#baz" to "evil.com/foo",
            "http://evil.com/foo;" to "evil.com/foo;",
            "http://evil.com/foo?bar;" to "evil.com/foo?bar;",
            "http://notrailingslash.com" to "notrailingslash.com/",
            "http://www.gotaport.com:1234/" to "www.gotaport.com/",
            "  http://www.google.com/  " to "www.google.com/",
            "https://www.securesite.com/" to "www.securesite.com/",
            "http://host.com//twoslashes?more//slashes" to "host.com/twoslashes?more//slashes"
        )
        vectors.forEach { (input, expression) ->
            assertEquals(input, expression, urls.expressions(input).first())
        }
    }

    @Test fun matchesCompleteOfficialV5ExpressionSets() {
        assertEquals(setOf(
            "a.b.com/1/2.html?param=1", "a.b.com/1/2.html", "a.b.com/", "a.b.com/1/",
            "b.com/1/2.html?param=1", "b.com/1/2.html", "b.com/", "b.com/1/"
        ), urls.expressions("http://a.b.com/1/2.html?param=1"))
        assertEquals(setOf("example.co.uk/1", "example.co.uk/"), urls.expressions("http://example.co.uk/1"))
        assertEquals(setOf("1.2.3.4/1/", "1.2.3.4/"), urls.expressions("http://1.2.3.4/1/"))
        assertEquals(setOf("a.b.c.d.e.f.com", "c.d.e.f.com", "d.e.f.com", "e.f.com", "f.com")
            .flatMap { listOf("$it/1.html", "$it/") }.toSet(), urls.expressions("http://a.b.c.d.e.f.com/1.html"))
    }

    @Test fun boundsHostPathProductAtThirty() {
        val expressions = urls.expressions("https://a.b.c.d.e.f.com/1/2/3/4/5?x=1")
        assertEquals(30, expressions.size)
        assertEquals(setOf("/1/2/3/4/5?x=1", "/1/2/3/4/5", "/", "/1/", "/1/2/", "/1/2/3/"),
            expressions.filter { it.startsWith("a.b.c.d.e.f.com/") }.map { it.removePrefix("a.b.c.d.e.f.com") }.toSet())
        assertFalse(expressions.any { it.startsWith("b.c.d.e.f.com/") })
    }

    @Test fun observesPrivateSuffixesWildcardAndExceptionRules() {
        assertEquals(setOf("a.b.tenant.github.io/", "b.tenant.github.io/", "tenant.github.io/"),
            urls.expressions("https://a.b.tenant.github.io/"))
        assertEquals(setOf("a.b.ck/"), urls.expressions("https://a.b.ck/"))
        assertEquals(setOf("a.www.ck/", "www.ck/"), urls.expressions("https://a.www.ck/"))
    }

    @Test fun preservesRealAirtelAndGoodMonkLinksWithoutChangingTheirHosts() {
        assertEquals(setOf("i.airtel.in/rc349sil3", "i.airtel.in/", "airtel.in/rc349sil3", "airtel.in/"),
            urls.expressions("i.airtel.in/rc349sil3"))
        assertEquals(setOf("weurl.co/GDMNKS/oo8a70", "weurl.co/", "weurl.co/GDMNKS/"),
            urls.expressions("https://weurl.co/GDMNKS/oo8a70"))
    }

    @Test fun convertsIdnAndUnicodeToAsciiBeforeHashing() {
        assertEquals(setOf("xn--bcher-kva.de/"), urls.expressions("https://BÜCHER.de/"))
        assertEquals(urls.expressions("https://bücher.de/"), urls.expressions("https://b%C3%BCcher.de/"))
        assertEquals("xn--bcher-kva.de/caf%C3%A9?q=%E2%9C%93", urls.expressions("https://bücher.de/café?q=✓").first())
        assertEquals(urls.expressions("https://example.com/"), urls.expressions("https://example\u3002com/"))
    }

    @Test fun escapesDecodedBytesAndKeepsEncodedFragmentLiteral() {
        assertEquals("example.com/%80%FF%00%0A%23%25", urls.expressions("https://example.com/%80%ff%00%0a%23%25#discard").first())
        assertEquals("example.com/a%20b?q=a+b%20c", urls.expressions("https://example.com/a b?q=a+b%20c").first())
        assertEquals("example.com/abcd", urls.expressions("https://example.com/a\tb\rc\nd").first())
    }

    @Test fun collapsesDotPathsButNeverCleansQueryParameters() {
        assertEquals("example.com/a/c/?x=/a//.././&q=?", urls.expressions("https://example.com//a/b/.././c/.?x=/a//.././&q=?").first())
        assertEquals("example.com/b", urls.expressions("https://example.com/a%2F..%2Fb").first())
        assertEquals("example.com/", urls.expressions("https://example.com/../../").first())
    }

    @Test fun omitsOnlySchemeCredentialsAndPort() {
        val plain = urls.expressions("https://example.com/a?x=1")
        assertEquals(plain, urls.expressions("http://user:password@example.com:8080/a?x=1#fragment"))
        assertEquals(plain, urls.expressions("example.com:443/a?x=1"))
        assertEquals(setOf("evil.com/"), urls.expressions("https://google.com@evil.com/"))
        assertEquals("example.com/?next=https://other.test/", urls.expressions("example.com?next=https://other.test/").first())
    }

    @Test fun canonicalizesOrdinaryOctalHexAndShortIpv4WithoutDns() {
        listOf("127.0.0.1", "127.1", "0177.0.0.1", "0x7f.1", "2130706433", "0x7f000001")
            .forEach { host -> assertEquals(host, setOf("127.0.0.1/"), urls.expressions("http://$host/")) }
        assertEquals(setOf("255.255.255.255/"), urls.expressions("http://0xFFFFFFFF/"))
        assertEquals(setOf("0.0.0.0/"), urls.expressions("http://0/"))
        assertEquals(setOf("1.2.3.4/"), urls.expressions("http://1.2.772/"))
    }

    @Test fun encodesKnownSha256ValuesAndSendsExactlyFourBytes() {
        assertEquals(setOf("c9mG4AkGXxgsELy2pF2z1u2pSY+JMGVK8mU/ipOM2AE="), urls.fullHashes("https://example.com/"))
        assertEquals(setOf("c9mG4A=="), urls.prefixes("https://example.com/"))
        assertTrue(urls.fullHashes("https://testsafebrowsing.appspot.com/s/phishing.html")
            .contains("771MOrRPMn6xPKlCrXx/CrR+wmCk0LgFFoSgGy7zUiA="))
        assertTrue(urls.prefixes("https://testsafebrowsing.appspot.com/s/phishing.html").contains("771MOg=="))
        val input = "https://a.b.c.d.e.f.com/1/2/3/4/5?q=test"
        assertTrue(urls.fullHashes(input).all { Base64.getDecoder().decode(it).size == 32 })
        assertTrue(urls.prefixes(input).all { Base64.getDecoder().decode(it).size == 4 })
    }

    @Test fun explicitlyRejectsUnsupportedIpv6AndAmbiguousIdn() {
        listOf("https://[2001:db8::1]/", "http://[::ffff:1.2.3.4]/", "http://[64:ff9b::1.2.3.4]/",
            "https://faß.de/", "https://ς.gr/", "https://a\u200db.com/").forEach(::assertUnchecked)
    }

    @Test fun explicitlyRejectsInvalidAndUnboundedInputs() {
        listOf("", "  ", "http:///example.com", "javascript:alert(1)", "ftp://example.com/",
            "https://example.com:70000/", "https://example.com:/", "https://example.com:bad/",
            "https://example.com\\@evil.com/", "https://%FF.example/", "https://exam ple.com/",
            "http://999.0.0.1/", "http://1.2.3.256/", "http://4294967296/", "http://09/",
            "http://example.com/" + "x".repeat(16_384), "http://example.com/%" + "25".repeat(1030) + "41",
            "https://example.com/\uD800").forEach(::assertUnchecked)
    }

    private fun assertUnchecked(input: String) {
        try {
            urls.expressions(input)
            fail("Expected explicit unsupported/invalid result: ${input.take(80)}")
        } catch (_: IllegalArgumentException) {
            // The reputation layer must translate this exception into Not checked.
        }
    }
}
