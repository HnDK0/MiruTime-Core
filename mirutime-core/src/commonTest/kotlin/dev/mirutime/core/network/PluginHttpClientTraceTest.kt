package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.CfChallenge
import dev.mirutime.core.host.CfChallengeSolver
import dev.mirutime.core.host.CfSolution
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

// PluginHttpClient's optional diagnostic observer (NetworkTraceEvent). The app
// path never wires it; plugin-tester renders these events as its NETWORK TRACE
// section. What matters here: one event per attempt, AFTER the body is read,
// hop numbering that restarts per redirect walk, and the CF flag on the
// response the detector actually judged.
class PluginHttpClientTraceTest {

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
        sink: (NetworkTraceEvent) -> Unit,
        solver: CfChallengeSolver? = null,
    ) = PluginHttpClient(
        metadata = metadata,
        systemInfo = FakeSystemInfo(),
        cookieStore = FakeCookieStore(),
        logger = RecordingLogger(),
        engine = engine,
        solver = solver,
        trace = sink,
    )

    @Test
    fun singleGetEmitsOneEventWithTheBodySize() = runTest {
        val events = mutableListOf<NetworkTraceEvent>()
        val body = "hello trace"
        val engine = MockEngine {
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.Server, "example"),
            )
        }

        client(engine, events::add).get("demo", "https://example.com/page")

        assertEquals(1, events.size, "one event per network attempt")
        val event = events.single()
        assertEquals("GET", event.method)
        assertEquals("https://example.com/page", event.url)
        assertEquals(200, event.status)
        assertEquals(body.length.toLong(), event.bodyBytes, "bodyBytes is read after the body")
        assertEquals(0, event.redirectHop, "the first hop is hop 0")
        assertFalse(event.cfBlocked)
        assertEquals("example", event.responseHeaders["server"]?.single())
        // The wire request carries the plugin UA, not just the caller's intent.
        assertTrue(
            event.requestHeaders.keys.any { it.equals(HttpHeaders.UserAgent, ignoreCase = true) },
            "requestHeaders must describe what was actually sent: ${event.requestKeys()}",
        )
    }

    @Test
    fun eachRedirectHopEmitsItsOwnEventInOrder() = runTest {
        val events = mutableListOf<NetworkTraceEvent>()
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/302") {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://example.com/final"),
                )
            } else {
                respond("final body")
            }
        }

        client(engine, events::add).get("demo", "https://example.com/302")

        assertEquals(listOf(0, 1), events.map { it.redirectHop }, "hops are 0-based and ordered")
        assertEquals(302, events[0].status)
        assertEquals(200, events[1].status)
        assertEquals(
            "https://example.com/final",
            events[1].url,
            "the second event describes the redirected request",
        )
    }

    @Test
    fun challengeIsFlaggedAndTheSolverRetryReportsFreshHops() = runTest {
        val events = mutableListOf<NetworkTraceEvent>()
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            if (engineCalls == 1) {
                respond(
                    content = "<html>Just a moment...</html>",
                    status = HttpStatusCode.ServiceUnavailable,
                    headers = headersOf(HttpHeaders.Server, "cloudflare"),
                )
            } else {
                respond("bypassed")
            }
        }
        val solver = object : CfChallengeSolver {
            override suspend fun solve(request: CfChallenge): CfSolution =
                CfSolution(mapOf("cf_clearance" to "token-1"), null)
        }

        client(engine, events::add, solver).get("demo", "https://example.com/page")

        assertEquals(2, events.size)
        assertTrue(events[0].cfBlocked, "the detector must flag the challenge response")
        assertFalse(events[1].cfBlocked, "the post-solver 200 is a clean response")
        assertEquals(
            listOf(0, 0),
            events.map { it.redirectHop },
            "the solver retry restarts the walk at hop 0",
        )
    }

    @Test
    fun aNullTraceEmitsNothing() = runTest {
        val engine = MockEngine { respond("body") }
        val client = PluginHttpClient(
            metadata = metadata,
            systemInfo = FakeSystemInfo(),
            cookieStore = FakeCookieStore(),
            logger = RecordingLogger(),
            engine = engine,
        )

        client.get("demo", "https://example.com/page")

        // No sink, no crash: the default (app) path is untouched by this feature.
        assertEquals(1, engine.requestHistory.size)
    }

    private fun NetworkTraceEvent.requestKeys(): String = requestHeaders.keys.joinToString()
}
