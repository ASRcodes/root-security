package com.asrcodes.dontmissmom

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal data class AlertTimeouts(val connectMs: Int = 15_000, val responseMs: Int = 90_000, val totalMs: Long = 120_000)
internal data class AlertHttpResponse(val status: Int, val body: String)
internal class AlertTransportException(val unconfirmed: Boolean, cause: Throwable) : IOException(cause)
internal class AlertDeadlineException : SocketTimeoutException("Alert deadline exceeded")

/** One POST, with a deadline covering auth, executor queueing, connect, write and response.
 * Never automatically replay an emergency POST: a lost response is not a failed delivery.
 */
internal class AlertHttpCall(
    private val endpoint: String,
    private val body: String,
    private val timeouts: AlertTimeouts = AlertTimeouts(),
    private val event: (String) -> Unit = {}
) {
    private var completed = false
    private var submitted = false
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var work: Future<*>? = null
    @Volatile private var deadline: Future<*>? = null

    fun start(
        token: ((Result<String>) -> Unit) -> Unit,
        sessionValid: () -> Boolean,
        callback: (Result<AlertHttpResponse>) -> Unit
    ): Subscription {
        fun finish(result: () -> Result<AlertHttpResponse>) {
            val value = synchronized(this) {
                if (completed) return
                completed = true
                result()
            }
            deadline?.cancel(false)
            event(value.fold({ "http=${it.status}" }, { "error=${it.javaClass.simpleName}" }))
            callback(value)
        }
        event("started")
        deadline = scheduler.schedule({
            finish { Result.failure(AlertTransportException(submitted, AlertDeadlineException())) }
            abort()
        }, timeouts.totalMs, TimeUnit.MILLISECONDS)
        token { result ->
            result.fold(onSuccess = { bearer ->
                synchronized(this) {
                    if (completed) return@fold
                    work = executor.submit {
                        try {
                            synchronized(this) { if (completed) return@submit }
                            if (!sessionValid()) throw AppProblem(R.string.session_expired)
                            val conn = URL(endpoint).openConnection() as HttpURLConnection
                            connection = conn
                            conn.requestMethod = "POST"
                            conn.connectTimeout = timeouts.connectMs
                            conn.readTimeout = timeouts.responseMs
                            // Streaming prevents HttpURLConnection from replaying a buffered POST.
                            conn.instanceFollowRedirects = false
                            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            conn.setRequestProperty("Authorization", "Bearer $bearer")
                            val bytes = body.toByteArray(Charsets.UTF_8)
                            conn.setFixedLengthStreamingMode(bytes.size)
                            conn.doOutput = true
                            conn.connect()
                            synchronized(this) {
                                if (completed) return@submit
                                if (!sessionValid()) throw AppProblem(R.string.session_expired)
                                submitted = true
                            }
                            event("posting")
                            conn.outputStream.use { it.write(bytes) }
                            val status = conn.responseCode
                            val raw = (if (status in 200..299) conn.inputStream else conn.errorStream)
                                ?.bufferedReader()?.use { it.readText() }.orEmpty()
                            finish { Result.success(AlertHttpResponse(status, raw)) }
                        } catch (error: Exception) {
                            finish { Result.failure(if (error is AppProblem) error else AlertTransportException(submitted, error)) }
                        } finally {
                            connection?.disconnect()
                        }
                    }
                }
            }, onFailure = { error -> finish { Result.failure(error) } })
        }
        return Subscription {
            synchronized(this) { completed = true }
            deadline?.cancel(false)
            event("cancelled")
            abort()
        }
    }

    private fun abort() {
        work?.cancel(true)
        // disconnect may block on some Android versions; never do it on the UI thread.
        closer.execute { connection?.disconnect() }
    }

    companion object {
        private val executor = Executors.newFixedThreadPool(4)
        private val scheduler = Executors.newSingleThreadScheduledExecutor()
        private val closer = Executors.newCachedThreadPool()
    }
}

class AlertProblem(val resource: Int, val retryable: Boolean = false, val unconfirmed: Boolean = false) : Exception()

internal object AlertResponses {
    fun problem(status: Int, success: Boolean, code: String): AlertProblem? {
        if (status in 200..299 && success) return null
        return when {
            code == "delivery_unconfirmed" -> AlertProblem(R.string.alert_unconfirmed, retryable = true, unconfirmed = true)
            code == "alert_in_progress" -> AlertProblem(R.string.alert_processing, retryable = true, unconfirmed = true)
            code == "voice_expired" -> AlertProblem(R.string.alert_voice_expired)
            status == 401 -> AlertProblem(R.string.session_expired)
            status == 403 -> AlertProblem(R.string.contact_unavailable)
            status == 404 && code == "contact_unavailable" -> AlertProblem(R.string.contact_unavailable)
            status == 404 -> AlertProblem(R.string.backend_update_required)
            status == 409 -> AlertProblem(R.string.alert_not_ready, retryable = true)
            status == 429 -> AlertProblem(R.string.alert_rate_limit, retryable = true)
            status == 408 || status >= 500 -> AlertProblem(R.string.alert_server_error, retryable = true, unconfirmed = true)
            status in 400..499 -> AlertProblem(R.string.alert_rejected)
            else -> AlertProblem(R.string.alert_unconfirmed, retryable = true, unconfirmed = true)
        }
    }
}
