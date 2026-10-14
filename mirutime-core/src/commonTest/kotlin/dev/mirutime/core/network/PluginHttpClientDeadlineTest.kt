package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Whole-call deadline (NoveLA NetworkClient wraps every call in
// withTimeout(90_000)) enforced as BOUNDARY CHECKS over an injectable clock —
// never as a coroutine timer, which would make runTest fast-forward to it
// before the engine starts. That also makes these deterministic: no real 90s.
class PluginHttpClientDeadlineTest {

    private val metadata = PluginMetadata(
        id = "dev.mirutime.demo",
        name = "Demo",
        baseUrl = "https://example.com",
        contentType = ContentType.NOVEL,
        language = "ru",
        api = 1,
        version = "0.1.0",
        sha256 = "abc",
    )

    private fun client(
        engine: MockEngine,
        clock: () -> Long,
        defaultDeadlineMs: Long? = 90_000,
    ) = PluginHttpClient(
        metadata = metadata,
        systemInfo = FakeSystemInfo(),
        cookieStore = FakeCookieStore(),
        logger = RecordingLogger(),
        engine = engine,
        defaultDeadlineMs = defaultDeadlineMs,
        clock = clock,
    )

    // The clock jumps past the budget between "start of call" and the first
    // hop check — the deadline must fire before any engine work happens.
    @Test
    fun expiredDeadlineThrowsTimeoutBeforeFirstSend() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("ok")
        }
        var reads = 0
        val clock = { if (reads++ == 0) 0L else 100_000L }

        assertFailsWith<HttpRequestTimeoutException> {
            client(engine, clock).get("demo", "https://example.com/x")
        }

        assertEquals(0, calls, "an expired deadline must not reach the engine")
    }

    @Test
    fun clockUnderDeadlineLeavesNormalFlowAlone() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("ok content")
        }

        val response = client(engine, clock = { 0L }).get("demo", "https://example.com/x")

        assertEquals(200, response.status.value)
        assertEquals("ok content", response.bodyAsText())
        assertEquals(1, calls)
    }

    // defaultDeadlineMs = null is the documented opt-out for special callers.
    @Test
    fun defaultDeadlineCanBeDisabled() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("ok")
        }
        var reads = 0
        val clock = { if (reads++ == 0) 0L else 100_000L }

        val response = client(engine, clock, defaultDeadlineMs = null)
            .get("demo", "https://example.com/x")

        assertEquals(200, response.status.value)
        assertEquals(1, calls)
    }

    // Boundary (b): the status-retry backoff must not sleep past the deadline.
    // The engine spends the budget on attempt 1, so there is no attempt 2.
    @Test
    fun statusRetryBackoffStopsAtTheDeadline() = runTest {
        var calls = 0
        var now = 0L
        val engine = MockEngine {
            calls++
            now = 100_000L
            respond("boom", HttpStatusCode.ServiceUnavailable)
        }

        assertFailsWith<HttpRequestTimeoutException> {
            client(engine, clock = { now }).get("demo", "https://example.com/x")
        }

        assertEquals(1, calls, "backoff must not buy a second attempt past the deadline")
    }
}
