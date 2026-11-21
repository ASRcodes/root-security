package org.suraksha.kavach.reputation

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import org.suraksha.core.PublicSuffixList
import org.suraksha.core.SafeBrowsingReply
import org.suraksha.core.SafeBrowsingResponse
import org.suraksha.core.SafeBrowsingUrls
import org.suraksha.kavach.data.SecretStore

data class OnlineState(val enabled: Boolean, val keyConfigured: Boolean, val lastStatus: String)
enum class ReputationStatus { NOT_CHECKED, NO_MATCH, THREAT }
data class ReputationResult(
    val status: ReputationStatus,
    val threats: Set<String> = emptySet(),
    val checkedAt: Long = 0L,
    internal val generation: Long = -1L
)

/** The transport boundary accepts only four-byte hash prefixes, never messages or full URLs. */
fun interface ReputationTransport {
    suspend fun lookup(prefixes: Set<String>, apiKey: String): String
}

@SuppressLint("UseKtx") // Successful durable writes are required before enabling online checks.
class OnlineUrlChecker(
    context: Context,
    private val secrets: SecretStore,
    scope: CoroutineScope,
    private val transport: ReputationTransport = GoogleSafeBrowsingTransport(scope),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime
) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("kavach_online_settings", Context.MODE_PRIVATE)
    private val lock = Any()
    private val settingsMutex = Mutex()
    private val checksMutex = Mutex()
    private val cache = ReputationCache()
    private val mutable = MutableStateFlow(OnlineState(false, false, "Loading online-check settings"))
    val state: StateFlow<OnlineState> = mutable.asStateFlow()
    private var generation = 0L
    private var key: String? = null
    private var helper: SafeBrowsingUrls? = null
    private var activeRequest: Job? = null

    private val initialized = scope.async(Dispatchers.IO) {
        try {
            val urls = loadUrls()
            synchronized(lock) { helper = urls }
            val savedKey = readKey()
            synchronized(lock) {
                key = savedKey
                val enabled = generation == 0L && savedKey != null && prefs.getBoolean("enabled", false)
                mutable.value = OnlineState(enabled, savedKey != null,
                    if (enabled) "Ready — no link checked yet" else "Online checks are off")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            synchronized(lock) {
                mutable.value = OnlineState(false, false, "Online checks unavailable — not checked")
            }
        }
    }

    suspend fun configure(key: String): Boolean {
        val supplied = key.trim()
        if (!validApiKey(supplied)) return false
        initialized.await()
        return settingsMutex.withLock {
            withContext(Dispatchers.IO) {
                val token = synchronized(lock) {
                    invalidateLocked()
                    prefs.edit().putBoolean("enabled", false).apply()
                    mutable.value = mutable.value.copy(enabled = false, lastStatus = "Saving encrypted API key")
                    generation
                }
                try {
                    val bytes = supplied.toByteArray(Charsets.UTF_8)
                    try { secrets.write(KEY_NAME, bytes) } finally { bytes.fill(0) }
                    synchronized(lock) {
                        this@OnlineUrlChecker.key = supplied
                        val enable = token == generation && helper != null && prefs.edit().putBoolean("enabled", true).commit()
                        if (!enable) prefs.edit().putBoolean("enabled", false).apply()
                        mutable.value = OnlineState(enable, true,
                            if (enable) "Key saved — no link checked yet" else "Key saved; online checks are off")
                        enable
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    synchronized(lock) {
                        if (generation == token) mutable.value = OnlineState(false, this@OnlineUrlChecker.key != null, "Could not save the API key")
                    }
                    false
                }
            }
        }
    }

    suspend fun enable(): Boolean {
        initialized.await()
        return settingsMutex.withLock {
            withContext(Dispatchers.IO) {
                val token = synchronized(lock) {
                    invalidateLocked()
                    mutable.value = mutable.value.copy(enabled = false, lastStatus = "Enabling online checks")
                    prefs.edit().putBoolean("enabled", false).apply()
                    generation
                }
                try {
                    val saved = readKey()
                    synchronized(lock) {
                        key = saved
                        val enabled = token == generation && helper != null && saved != null && prefs.edit().putBoolean("enabled", true).commit()
                        if (!enabled) prefs.edit().putBoolean("enabled", false).apply()
                        mutable.value = OnlineState(enabled, saved != null,
                            if (enabled) "Ready — no link checked yet" else "Online checks are off; a saved key is required")
                        enabled
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    synchronized(lock) {
                        if (token == generation) mutable.value = OnlineState(false, false, "Saved key unavailable — not checked")
                    }
                    false
                }
            }
        }
    }

    fun disable() {
        synchronized(lock) {
            invalidateLocked()
            mutable.value = mutable.value.copy(enabled = false, lastStatus = "Online checks are off")
            prefs.edit().putBoolean("enabled", false).apply()
        }
    }

    suspend fun clearKey() {
        disable()
        initialized.await()
        settingsMutex.withLock {
            withContext(Dispatchers.IO + NonCancellable) {
                synchronized(lock) {
                    invalidateLocked()
                    key = null
                    mutable.value = OnlineState(false, false, "Online key removed")
                }
                try {
                    secrets.remove(KEY_NAME)
                    check(prefs.edit().putBoolean("enabled", false).commit())
                } catch (error: Exception) {
                    synchronized(lock) { mutable.value = OnlineState(false, true, "Key removal failed; online checks are off") }
                    throw error
                }
            }
        }
    }

    /** One bounded GET: links beyond the first six remain explicitly NOT_CHECKED in the result. */
    suspend fun checkAll(urls: List<String>): Map<String, ReputationResult> {
        val result = urls.distinct().associateWith { ReputationResult(ReputationStatus.NOT_CHECKED) }.toMutableMap()
        if (result.isEmpty()) return result
        initialized.await()
        return checksMutex.withLock {
            val request = synchronized(lock) {
                val currentKey = key
                val currentHelper = helper
                if (!mutable.value.enabled || currentKey == null || currentHelper == null) null
                else RequestState(generation, currentKey, currentHelper)
            } ?: return@withLock result
            val hashes = linkedMapOf<String, Set<String>>()
            result.keys.take(MAX_URLS).forEach { url ->
                try { hashes[url] = request.urls.fullHashes(url) } catch (_: IllegalArgumentException) { /* Not checked. */ }
            }
            val prefixes = hashes.values.flatten().map(::hashPrefix).toSet()
            if (prefixes.isEmpty() || prefixes.size > MAX_PREFIXES) return@withLock result
            val records = synchronized(lock) {
                if (!currentLocked(request.generation)) return@withLock result
                cache.snapshot(prefixes, elapsedClock()).toMutableMap()
            }
            val missing = prefixes - records.keys
            var batch = emptyMap<String, PrefixRecord>()
            if (missing.isNotEmpty()) {
                try {
                    val reply = lookup(missing, request) ?: return@withLock result
                    synchronized(lock) {
                        if (!currentLocked(request.generation)) return@withLock result
                        batch = cache.record(missing, reply, wallClock(), elapsedClock())
                    }
                } catch (_: TimeoutCancellationException) {
                    status(request.generation, "Lookup timed out — not checked")
                    return@withLock result
                } catch (cancelled: CancellationException) {
                    if (synchronized(lock) { !currentLocked(request.generation) }) return@withLock result
                    throw cancelled
                } catch (error: ReputationHttpException) {
                    status(request.generation, SafeBrowsingHttpDiagnostics.statusMessage(error))
                    return@withLock result
                } catch (_: Exception) {
                    status(request.generation, "Lookup unavailable — not checked")
                    return@withLock result
                }
            }
            synchronized(lock) {
                if (!currentLocked(request.generation)) return@withLock result
                val now = elapsedClock()
                records.entries.removeAll { !it.value.fresh(now) }
                // A zero-duration answer can be used for this response but is never cached.
                records.putAll(batch)
                hashes.forEach { (url, fullHashes) ->
                    result[url] = reputationFor(fullHashes, records).copy(generation = request.generation)
                }
                mutable.value = mutable.value.copy(lastStatus = when {
                    result.values.any { it.status == ReputationStatus.THREAT } -> "Known threat match found"
                    result.values.any { it.status == ReputationStatus.NOT_CHECKED } -> "Some links could not be checked"
                    else -> "No known threat found; this is not a safety guarantee"
                })
            }
            result
        }
    }

    /** Revalidate immediately before using an answer if settings could have changed since await. */
    fun isCurrent(result: ReputationResult): Boolean = synchronized(lock) {
        result.status != ReputationStatus.NOT_CHECKED && currentLocked(result.generation)
    }

    private suspend fun lookup(prefixes: Set<String>, request: RequestState): SafeBrowsingReply? = coroutineScope {
        val pending = async(start = CoroutineStart.LAZY) {
            withTimeout(8_000) { SafeBrowsingResponse.parse(transport.lookup(prefixes, request.key)) }
        }
        synchronized(lock) {
            if (!currentLocked(request.generation)) { pending.cancel(); return@coroutineScope null }
            activeRequest = pending
            mutable.value = mutable.value.copy(lastStatus = "Checking link reputation")
        }
        try { pending.start(); pending.await() }
        finally { synchronized(lock) { if (activeRequest === pending) activeRequest = null } }
    }

    private fun status(token: Long, value: String) = synchronized(lock) {
        if (currentLocked(token)) mutable.value = mutable.value.copy(lastStatus = value)
    }

    private fun currentLocked(token: Long) = generation == token && mutable.value.enabled && key != null

    private fun invalidateLocked() {
        generation++
        cache.clear()
        activeRequest?.cancel()
        activeRequest = null
    }

    private fun readKey(): String? = secrets.read(KEY_NAME)?.let { bytes ->
        try { bytes.toString(Charsets.UTF_8).takeIf(::validApiKey) } finally { bytes.fill(0) }
    }

    private fun loadUrls(): SafeBrowsingUrls {
        val bytes = context.assets.open("public_suffix_list.dat").use { it.readBytes() }
        val manifest = context.assets.open("asset_manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val expected = manifest.getJSONObject("files").getString("public_suffix_list.dat")
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(expected == actual)
        return SafeBrowsingUrls(PublicSuffixList(bytes.toString(Charsets.UTF_8)))
    }

    private data class RequestState(val generation: Long, val key: String, val urls: SafeBrowsingUrls)
    private companion object {
        const val KEY_NAME = "safe_browsing_api_key"
        const val MAX_URLS = 6
        const val MAX_PREFIXES = 180
    }
}

private fun validApiKey(key: String): Boolean = key.length in 20..256 && key.all {
    it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-'
}

/** Fixed-origin HTTPS transport. No redirect following, body, URL submission, or request logging. */
class GoogleSafeBrowsingTransport(private val scope: CoroutineScope) : ReputationTransport {
    override suspend fun lookup(prefixes: Set<String>, apiKey: String): String {
        require(validApiKey(apiKey))
        val endpoint = safeBrowsingEndpoint(prefixes)
        return suspendCancellableCoroutine { continuation ->
            val connection = endpoint.openConnection() as HttpsURLConnection
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-store")
            val worker = scope.launch(Dispatchers.IO) {
                try {
                    ensureActive()
                    val deadline = System.nanoTime() + 7_000_000_000L
                    connection.connect()
                    connection.readTimeout = remainingMillis(deadline)
                    val code = connection.responseCode
                    if (code != 200) throw ReputationHttpException(code, readErrorReason(connection, deadline))
                    require(connection.contentLengthLong <= MAX_RESPONSE_BYTES || connection.contentLengthLong < 0)
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            ensureActive()
                            connection.readTimeout = remainingMillis(deadline)
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= MAX_RESPONSE_BYTES) { "Reputation response too large" }
                            output.write(buffer, 0, count)
                        }
                    }
                    val body = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
                    continuation.resume(body)
                } catch (cancelled: CancellationException) {
                    continuation.cancel(cancelled)
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                } finally { connection.disconnect() }
            }
            continuation.invokeOnCancellation { worker.cancel(); connection.disconnect() }
            worker.invokeOnCompletion { error ->
                if (error != null && continuation.isActive) continuation.cancel(CancellationException("Lookup cancelled").apply { initCause(error) })
            }
        }
    }

    private suspend fun readErrorReason(connection: HttpsURLConnection, deadline: Long): GoogleApiErrorReason? {
        return try {
            // Diagnostics get at most another 500ms, within the existing request deadline.
            // A slow, malformed or oversized body must not discard the known HTTP status.
            val errorDeadline = minOf(deadline, System.nanoTime() + 500_000_000L)
            currentCoroutineContext().ensureActive()
            connection.readTimeout = remainingMillis(errorDeadline)
            if (connection.contentLengthLong > SafeBrowsingHttpDiagnostics.MAX_ERROR_BYTES) return null
            val output = ByteArrayOutputStream()
            connection.errorStream?.use { input ->
                val buffer = ByteArray(1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    connection.readTimeout = remainingMillis(errorDeadline)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > SafeBrowsingHttpDiagnostics.MAX_ERROR_BYTES) return null
                    output.write(buffer, 0, count)
                }
            } ?: return null
            SafeBrowsingHttpDiagnostics.reason(output.toByteArray())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun remainingMillis(deadline: Long): Int {
        val millis = (deadline - System.nanoTime()) / 1_000_000
        check(millis > 0) { "Reputation request timed out" }
        return millis.coerceAtMost(6_000).toInt().coerceAtLeast(1)
    }

    private companion object { const val MAX_RESPONSE_BYTES = 1_048_576 }
}

internal fun safeBrowsingEndpoint(prefixes: Set<String>): URL {
    require(prefixes.isNotEmpty() && prefixes.size <= 180)
    val query = prefixes.joinToString("&") { prefix ->
        val bytes = java.util.Base64.getDecoder().decode(prefix)
        require(bytes.size == 4 && java.util.Base64.getEncoder().encodeToString(bytes) == prefix)
        "hashPrefixes=" + URLEncoder.encode(prefix, "UTF-8")
    }
    val address = "https://safebrowsing.googleapis.com/v5/hashes:search?alt=json&$query"
    require(address.length <= 7500) { "Reputation query exceeds the request limit" }
    return URL(address)
}

internal class ReputationHttpException(val statusCode: Int, val reason: GoogleApiErrorReason? = null) :
    Exception("Reputation HTTP request failed")

/** Fixed advice only; no response message, metadata, project identifier or credential is retained. */
internal enum class GoogleApiErrorReason(val advice: String) {
    SERVICE_DISABLED("Enable Safe Browsing API in the key's Google Cloud project — not checked"),
    API_KEY_SERVICE_BLOCKED("Allow Safe Browsing API in the key's API restrictions — not checked"),
    API_KEY_ANDROID_APP_BLOCKED("The key's Android app restriction blocked this client — not checked"),
    API_KEY_HTTP_REFERRER_BLOCKED("The key's website referrer restriction blocked this Android client — not checked"),
    API_KEY_IP_ADDRESS_BLOCKED("The key's allowed IP addresses blocked this request — not checked"),
    API_KEY_INVALID("Google rejected the API key as invalid; correct or replace it — not checked")
}

/** https://docs.cloud.google.com/java/docs/reference/proto-google-common-protos/latest/com.google.api.ErrorReason */
internal object SafeBrowsingHttpDiagnostics {
    const val MAX_ERROR_BYTES = 64 * 1024

    fun statusMessage(error: ReputationHttpException): String {
        if (error.statusCode in setOf(400, 401, 403)) error.reason?.let { return it.advice }
        return when (error.statusCode) {
            401 -> "API key was not accepted — not checked"
            403 -> "Check the API setup or quota — not checked"
            429 -> "Rate limit reached — not checked"
            else -> "Lookup unavailable — not checked"
        }
    }

    fun reason(bytes: ByteArray): GoogleApiErrorReason? {
        if (bytes.isEmpty() || bytes.size > MAX_ERROR_BYTES) return null
        return try {
            val body = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            if (!boundedDepth(body)) return null
            val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
            val error = root["error"] as? JsonObject ?: return null
            val details = error["details"] as? JsonArray ?: return null
            if (details.size > 16) return null
            details.mapNotNull { element ->
                val detail = element as? JsonObject ?: return@mapNotNull null
                if (detail.text("@type") != "type.googleapis.com/google.rpc.ErrorInfo" ||
                    detail.text("domain") != "googleapis.com") return@mapNotNull null
                GoogleApiErrorReason.entries.firstOrNull { it.name == detail.text("reason") }
            }.distinct().singleOrNull()
        } catch (_: Exception) {
            null // The raw response and parsing error must never escape into logs or UI.
        }
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun boundedDepth(body: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in body) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                }
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 16) return false
                '}', ']' -> if (--depth < 0) return false
            }
        }
        return !quoted && depth == 0
    }
}
