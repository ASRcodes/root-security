package com.asrcodes.dontmissmom

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AlertHttpCallTest {
    private val servers = mutableListOf<ServerSocket>()
    private val workers = Executors.newCachedThreadPool()
    @After fun cleanup() { servers.forEach { it.close() }; workers.shutdownNow() }

    private fun server(path: String = "/v2/sendEmergency", handle: (HttpExchange) -> Unit): String {
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        servers += server
        workers.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                workers.execute { socket.use { handle(HttpExchange(it)) } }
            }
        }
        return "http://127.0.0.1:${server.localPort}$path"
    }

    // Minimal loopback HTTP fixture, using APIs also available to Android unit-test compilation.
    private class HttpExchange(private val socket: Socket) {
        private val input = socket.getInputStream().buffered()
        private fun line(): String = buildString {
            while (true) {
                val next = input.read()
                if (next == -1 || next == 10) break
                if (next != 13) append(next.toChar())
            }
        }
        val requestMethod = line().substringBefore(' ')
        val requestHeaders = mutableMapOf<String, String>()
        val requestBody: ByteArrayInputStream
        val responseBody get() = socket.getOutputStream()
        init {
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                requestHeaders[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
            }
            requestBody = ByteArrayInputStream(ByteArray(requestHeaders["content-length"]?.toInt() ?: 0) { input.read().toByte() })
        }
        fun sendResponseHeaders(status: Int, length: Long) {
            responseBody.write("HTTP/1.1 $status Test\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
        }
        fun close() { socket.close() }
    }

    private fun respond(exchange: HttpExchange, status: Int = 200, body: String = "{\"success\":true}") {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun execute(url: String, timeouts: AlertTimeouts = AlertTimeouts()): Result<AlertHttpResponse> {
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<AlertHttpResponse>>()
        AlertHttpCall(url, "{\"alertId\":\"same-id\"}", timeouts).start({ it(Result.success("test-token")) }, { true }) {
            result.set(it); done.countDown()
        }
        assertTrue("Request never completed", done.await(timeouts.totalMs + 2_000, TimeUnit.MILLISECONDS))
        return result.get()
    }

    @Test fun normalRequestUsesOneAuthenticatedPost() {
        val calls = AtomicInteger()
        val url = server {
            assertEquals("POST", it.requestMethod)
            assertEquals("Bearer test-token", it.requestHeaders["authorization"])
            assertTrue(it.requestBody.bufferedReader().readText().contains("same-id"))
            calls.incrementAndGet(); respond(it)
        }
        assertEquals(200, execute(url).getOrThrow().status)
        assertEquals(1, calls.get())
    }

    @Test fun coldStartLongerThanBothOldTimeoutsStillSucceedsWithOnePost() {
        val calls = AtomicInteger()
        val paths = listOf("/v2/sendEmergency", "/v2/profile/phone", "/v2/requests", "/v2/requests/incoming/accept")
        val received = CountDownLatch(paths.size)
        val finished = CountDownLatch(paths.size)
        val results = paths.map { path ->
            val result = AtomicReference<Result<AlertHttpResponse>>()
            val url = server(path) {
                calls.incrementAndGet(); received.countDown()
                Thread.sleep(60_000) // Beyond both the former 25-second read and 45-second deadline.
                respond(it)
            }
            AlertHttpCall(url, "{}").start({ it(Result.success("token")) }, { true }) { result.set(it); finished.countDown() }
            result
        }
        assertTrue(received.await(5, TimeUnit.SECONDS))
        assertFalse("Must still be sending at the old deadline", finished.await(46, TimeUnit.SECONDS))
        assertEquals(paths.size.toLong(), finished.count)
        assertTrue(finished.await(25, TimeUnit.SECONDS))
        results.forEach { assertEquals(200, it.get().getOrThrow().status) }
        assertEquals(paths.size, calls.get())
    }

    @Test fun connectionRefusedIsKnownNotSubmitted() {
        val port = ServerSocket(0).use { it.localPort }
        val error = execute("http://127.0.0.1:$port/v2/sendEmergency").exceptionOrNull() as AlertTransportException
        assertFalse(error.unconfirmed)
    }

    @Test fun lostResponseIsUnconfirmedAndNeverAutomaticallyReplayed() {
        val calls = AtomicInteger()
        val url = server { calls.incrementAndGet(); it.requestBody.readBytes(); it.close() }
        val error = execute(url).exceptionOrNull() as AlertTransportException
        assertTrue(error.unconfirmed)
        assertEquals(1, calls.get())
    }

    @Test fun readTimeoutAndTotalDeadlineEachCompleteOnceWithoutRetry() {
        listOf(AlertTimeouts(1000, 100, 2000), AlertTimeouts(1000, 2000, 100)).forEach { policy ->
            val calls = AtomicInteger()
            val callbacks = AtomicInteger()
            val done = CountDownLatch(1)
            val url = server { calls.incrementAndGet(); Thread.sleep(400); runCatching { respond(it) } }
            AlertHttpCall(url, "{}", policy).start({ it(Result.success("token")) }, { true }) {
                callbacks.incrementAndGet()
                assertTrue((it.exceptionOrNull() as AlertTransportException).unconfirmed)
                done.countDown()
            }
            assertTrue(done.await(3, TimeUnit.SECONDS))
            Thread.sleep(500)
            assertEquals(1, calls.get()); assertEquals(1, callbacks.get())
        }
    }

    @Test fun deadlineDuringAuthenticationPreventsLatePost() {
        val calls = AtomicInteger()
        val done = CountDownLatch(1)
        lateinit var tokenReady: (Result<String>) -> Unit
        val url = server { calls.incrementAndGet(); respond(it) }
        AlertHttpCall(url, "{}", AlertTimeouts(totalMs = 100)).start({ tokenReady = it }, { true }) {
            assertFalse((it.exceptionOrNull() as AlertTransportException).unconfirmed)
            done.countDown()
        }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        tokenReady(Result.success("late-token"))
        Thread.sleep(200)
        assertEquals(0, calls.get())
    }

    @Test fun cancellationStopsQueuedAuthenticationAndSuppressesLateCallbacks() {
        val calls = AtomicInteger()
        val callbacks = AtomicInteger()
        lateinit var tokenReady: (Result<String>) -> Unit
        val url = server { calls.incrementAndGet(); respond(it) }
        val operation = AlertHttpCall(url, "{}", AlertTimeouts(totalMs = 100)).start({ tokenReady = it }, { true }) { callbacks.incrementAndGet() }
        operation.remove()
        tokenReady(Result.success("late-token"))
        Thread.sleep(200)
        assertEquals(0, calls.get()); assertEquals(0, callbacks.get())
    }

    @Test fun leavingAnInFlightRequestSuppressesItsEventualResult() {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val url = server { received.countDown(); release.await(2, TimeUnit.SECONDS); runCatching { respond(it) } }
        val operation = AlertHttpCall(url, "{}").start({ it(Result.success("token")) }, { true }) { callbacks.incrementAndGet() }
        assertTrue(received.await(2, TimeUnit.SECONDS))
        operation.remove(); release.countDown()
        Thread.sleep(200)
        assertEquals(0, callbacks.get())
    }

    @Test fun sessionChangePreventsPost() {
        val calls = AtomicInteger()
        val done = CountDownLatch(1)
        val url = server { calls.incrementAndGet(); respond(it) }
        AlertHttpCall(url, "{}").start({ it(Result.success("token")) }, { false }) {
            assertEquals(R.string.session_expired, (it.exceptionOrNull() as AppProblem).resource)
            done.countDown()
        }
        assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals(0, calls.get())
    }

    @Test fun realHttpErrorsAreNotConvertedToConnectivityFailuresOrRetried() {
        listOf(400, 401, 403, 404, 409, 429, 500, 502, 503, 504).forEach { status ->
            val calls = AtomicInteger()
            val url = server { calls.incrementAndGet(); respond(it, status, "{\"error\":\"test\"}") }
            assertEquals(status, execute(url).getOrThrow().status)
            val problem = AlertResponses.problem(status, false, "")!!
            assertNotEquals(R.string.alert_connection_failed, problem.resource)
            assertEquals(status >= 500, problem.unconfirmed)
            assertEquals(1, calls.get())
        }
        assertNull(AlertResponses.problem(200, true, ""))
        assertTrue(AlertResponses.problem(200, false, "")!!.unconfirmed)
        assertTrue(AlertResponses.problem(409, false, "delivery_unconfirmed")!!.unconfirmed)
        assertFalse(AlertResponses.problem(409, false, "voice_expired")!!.retryable)
        assertEquals(R.string.backend_update_required, AlertResponses.problem(404, false, "")!!.resource)
    }
}
