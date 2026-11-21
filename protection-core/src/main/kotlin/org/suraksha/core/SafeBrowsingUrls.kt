package org.suraksha.core

import java.io.ByteArrayOutputStream
import java.net.IDN
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * URL expressions for the Safe Browsing v5 hash-prefix protocol. This class never resolves a host
 * or visits a URL. Unsupported inputs throw so callers can report "Not checked", not a clean result.
 *
 * Specification: https://developers.google.com/safe-browsing/reference/URLs.and.Hashing
 * IPv6 and IDNA deviation characters need a browser-compatible parser and are deliberately rejected.
 */
class SafeBrowsingUrls(private val suffixes: PublicSuffixList) {
    fun expressions(input: String): Set<String> {
        val url = canonicalize(input)
        val hosts = linkedSetOf(url.host)
        if (!url.ipv4) {
            suffixes.registrableDomain(url.host)?.let { registrable ->
                val labels = url.host.split('.')
                val baseSize = registrable.split('.').size
                for (size in baseSize..minOf(baseSize + 3, labels.size)) {
                    hosts += labels.takeLast(size).joinToString(".")
                }
            }
        }

        val paths = linkedSetOf<String>()
        if (url.query != null) paths += url.path + "?" + url.query
        paths += url.path
        paths += "/"
        var slash = url.path.indexOf('/', startIndex = 1)
        var prefixes = 1 // The root is the first of four path prefixes.
        while (slash >= 0 && prefixes < 4) {
            paths += url.path.substring(0, slash + 1)
            prefixes++
            slash = url.path.indexOf('/', startIndex = slash + 1)
        }
        return hosts.flatMapTo(linkedSetOf()) { host -> paths.map { host + it } }.also {
            check(it.size <= 30) { "Safe Browsing expression limit exceeded" }
        }
    }

    /** Base64-encoded 32-byte hashes, retained locally to verify returned prefix candidates. */
    fun fullHashes(input: String): Set<String> = digestExpressions(input).mapTo(linkedSetOf()) {
        Base64.getEncoder().encodeToString(it)
    }

    /** Only these four-byte prefixes may be sent to v5 hashes.search. */
    fun prefixes(input: String): Set<String> = digestExpressions(input).mapTo(linkedSetOf()) {
        Base64.getEncoder().encodeToString(it.copyOf(4))
    }

    private fun digestExpressions(input: String): List<ByteArray> = expressions(input).map {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.US_ASCII))
    }

    private data class CanonicalUrl(val host: String, val path: String, val query: String?, val ipv4: Boolean)

    private fun canonicalize(input: String): CanonicalUrl {
        require(input.length <= MAX_INPUT_LENGTH) { "URL is too long" }
        val withoutControls = input.filterNot { it == '\t' || it == '\r' || it == '\n' }
            .trim { it <= ' ' }.substringBefore('#')
        require(withoutControls.isNotEmpty()) { "URL is empty" }

        // Keep decoded bytes losslessly: an escaped %80 in a path must stay %80, not become U+FFFD.
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(withoutControls))
            ByteArray(encoded.remaining()).also { encoded.get(it) }
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("URL contains invalid Unicode")
        }
        val decoded = recursivelyUnescape(bytes).toString(Charsets.ISO_8859_1)
        require('\\' !in decoded) { "Backslash URL normalization is unsupported" }
        val scheme = SCHEME.find(decoded)
        val rest = if (scheme != null) {
            require(scheme.groupValues[1].lowercase(Locale.ROOT) in setOf("http", "https")) {
                "Only HTTP and HTTPS URLs are supported"
            }
            decoded.substring(scheme.value.length)
        } else {
            decoded.removePrefix("//")
        }

        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        require(authority.isNotEmpty()) { "URL has no hostname" }
        val hostPort = authority.substringAfterLast('@')
        require(!hostPort.startsWith('[') && !hostPort.contains(']')) {
            "IPv6 URLs are not supported by this canonicalizer"
        }
        require(hostPort.count { it == ':' } <= 1) { "Invalid or unsupported host authority" }
        val hostBytes = hostPort.substringBefore(':')
        if (':' in hostPort) {
            val port = hostPort.substringAfter(':')
            require(port.isNotEmpty() && port.all { it in '0'..'9' } && port.toIntOrNull()?.let { it in 1..65535 } == true) {
                "Invalid URL port or scheme"
            }
        }
        val host = canonicalHost(hostBytes)
        val remainder = rest.substring(authorityEnd)
        val question = remainder.indexOf('?')
        val rawPath = if (question >= 0) remainder.substring(0, question) else remainder
        val query = if (question >= 0) escape(remainder.substring(question + 1)) else null
        val path = escape(cleanPath(rawPath.ifEmpty { "/" }))
        return CanonicalUrl(host.first, path, query, host.second)
    }

    private fun canonicalHost(rawBytes: String): Pair<String, Boolean> {
        val unicode = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(rawBytes.toByteArray(Charsets.ISO_8859_1))).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Hostname contains invalid UTF-8")
        }
        require(unicode.none { it in IDNA_DEVIATIONS }) { "This IDN requires unsupported IDNA normalization" }
        val dotted = unicode.replace('\u3002', '.').replace('\uff0e', '.').replace('\uff61', '.')
            .trim('.').replace(DOTS, ".")
        require(dotted.isNotEmpty()) { "URL has no hostname" }
        // java.net.IDN uses RFC 3490/Nameprep. Reject its known deviation characters above rather
        // than silently checking a different domain from a modern browser (for example faß.de).
        val ascii = IDN.toASCII(dotted, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        require(ascii.length <= 253 && ascii.split('.').all { it.isNotEmpty() }) { "Invalid hostname" }
        val last = ascii.substringAfterLast('.')
        val numeric = last.all { it in '0'..'9' } || last.startsWith("0x")
        return if (numeric) canonicalIpv4(ascii) to true else ascii to false
    }

    private fun canonicalIpv4(host: String): String {
        val parts = host.split('.')
        require(parts.size in 1..4) { "Invalid IPv4 address" }
        val numbers = parts.map { part ->
            val radix: Int
            val digits: String
            when {
                part.startsWith("0x") -> { radix = 16; digits = part.drop(2) }
                part.length > 1 && part.startsWith('0') -> { radix = 8; digits = part.drop(1) }
                else -> { radix = 10; digits = part }
            }
            require(digits.isNotEmpty() && digits.all {
                when (radix) { 16 -> it in '0'..'9' || it in 'a'..'f'; 8 -> it in '0'..'7'; else -> it in '0'..'9' }
            }) { "Invalid IPv4 number" }
            requireNotNull(digits.toLongOrNull(radix)) { "IPv4 number is too large" }
        }
        require(numbers.dropLast(1).all { it <= 255 }) { "IPv4 component exceeds one byte" }
        val lastBytes = 5 - numbers.size
        require(numbers.last() < (1L shl (lastBytes * 8))) { "IPv4 address is out of range" }
        var value = 0L
        numbers.dropLast(1).forEach { value = (value shl 8) or it }
        value = (value shl (lastBytes * 8)) or numbers.last()
        return (3 downTo 0).joinToString(".") { ((value shr (it * 8)) and 255).toString() }
    }

    private fun cleanPath(raw: String): String {
        require(raw.startsWith('/')) { "URL path must be absolute" }
        val components = ArrayList<String>()
        raw.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (components.isNotEmpty()) components.removeAt(components.lastIndex)
                else -> components += part
            }
        }
        val path = "/" + components.joinToString("/")
        val last = raw.substringAfterLast('/')
        return if (path != "/" && (raw.endsWith('/') || last == "." || last == "..")) "$path/" else path
    }

    private fun recursivelyUnescape(initial: ByteArray): ByteArray {
        var bytes = initial
        repeat(MAX_UNESCAPE_PASSES) {
            val output = ByteArrayOutputStream(bytes.size)
            var changed = false
            var i = 0
            while (i < bytes.size) {
                val high = if (i + 2 < bytes.size) hex(bytes[i + 1]) else -1
                val low = if (i + 2 < bytes.size) hex(bytes[i + 2]) else -1
                if (bytes[i] == '%'.code.toByte() && high >= 0 && low >= 0) {
                    output.write((high shl 4) or low)
                    i += 3
                    changed = true
                } else {
                    output.write(bytes[i].toInt() and 255)
                    i++
                }
            }
            if (!changed) return bytes
            bytes = output.toByteArray()
        }
        throw IllegalArgumentException("URL percent encoding is too deeply nested")
    }

    private fun hex(byte: Byte): Int = when (val c = byte.toInt() and 255) {
        in 48..57 -> c - 48
        in 65..70 -> c - 65 + 10
        in 97..102 -> c - 97 + 10
        else -> -1
    }

    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            val byte = char.code
            if (byte <= 32 || byte >= 127 || char == '#' || char == '%') {
                append('%').append(HEX[byte ushr 4]).append(HEX[byte and 15])
            } else append(char)
        }
    }

    private companion object {
        const val MAX_INPUT_LENGTH = 16_384
        const val MAX_UNESCAPE_PASSES = 1024
        const val HEX = "0123456789ABCDEF"
        val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")
        val DOTS = Regex("\\.+")
        val IDNA_DEVIATIONS = setOf('\u00df', '\u1e9e', '\u03c2', '\u200c', '\u200d')
    }
}
