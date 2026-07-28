package dev.itayp.nescioquid.openrouter

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import org.springframework.web.client.ResourceAccessException
import java.util.concurrent.TimeUnit
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Guards the failure that hung a CI job for fifty minutes: a provider opens a stream, emits a
 * little, then goes silent forever. Without a socket-level read timeout the collector blocks in
 * `InputStream.read` — and coroutine cancellation cannot interrupt a parked read, so nothing
 * downstream can rescue it either.
 *
 * Uses a real server rather than MockRestServiceServer, because the whole point is the socket
 * behaviour: the mock serves a byte array and can never stall mid-body.
 */
class AiClientStreamTimeoutTest {

    private lateinit var server: HttpServer
    private val release = CountDownLatch(1)

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            // One well-formed event, flushed, so the client is committed to reading the stream…
            exchange.responseBody.write("""data: {"choices":[{"delta":{"content":"hi"}}]}""".toByteArray())
            exchange.responseBody.write("\n\n".toByteArray())
            exchange.responseBody.flush()
            // …and then nothing, until the test tears the server down.
            release.await(30, TimeUnit.SECONDS)
            runCatching { exchange.close() }
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        release.countDown()
        server.stop(0)
    }

    private fun client(readTimeout: Duration) = AiClient(
        properties = AiClientProperties(
            apiKey = "k",
            baseUrl = "http://127.0.0.1:${server.address.port}",
            configuredModels = emptySet(),
            readTimeout = readTimeout,
        ),
        callGate = { _, _ -> },
        callListener = RecordingListener(),
    )

    @Test
    fun `a stalled stream fails instead of hanging forever`() = runTest {
        val listener = RecordingListener()
        val client = AiClient(
            properties = AiClientProperties(
                apiKey = "k",
                baseUrl = "http://127.0.0.1:${server.address.port}",
                configuredModels = emptySet(),
                readTimeout = Duration.ofMillis(750),
            ),
            callGate = { _, _ -> },
            callListener = listener,
        )

        val startedAt = System.nanoTime()
        val error = runCatching { client.chatStream(testRequest(), testContext).toList() }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        // It must fail, not complete: a stream cut short mid-generation is not a valid response.
        assertTrue(error != null, "expected the stalled stream to fail, but it completed normally")
        // And it must fail promptly — the point is bounding the stall, not merely reporting it.
        assertTrue(elapsedMs < 15_000, "expected failure within seconds, took ${elapsedMs}ms")
        // Surfaced as the same type the blocking path uses for transport failures, so a caller
        // handles both uniformly rather than special-casing a raw IOException from the stream.
        assertIs<ResourceAccessException>(error)
        // A raw IOException is not a RuntimeException, so accounting silently missed these before.
        assertTrue(listener.failures == 1, "expected one recorded failure, got ${listener.failures}")
    }

    @Test
    fun `the deltas delivered before the stall are still handed to the collector`() = runTest {
        val collected: MutableList<ChatStreamEvent> = mutableListOf()
        runCatching {
            client(Duration.ofMillis(750)).chatStream(testRequest(), testContext).collect { collected += it }
        }

        // Whatever arrived before the stall is real output and is not rolled back.
        assertTrue(
            collected.contains(ChatStreamEvent.ContentDelta("hi")),
            "expected the pre-stall delta to reach the collector, got $collected",
        )
    }
}
