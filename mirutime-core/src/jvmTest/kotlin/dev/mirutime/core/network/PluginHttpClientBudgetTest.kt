package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import dev.mirutime.core.network.cf.CfOptions
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Whole-call timeout budget (NoveLA BudgetedCall): the caller's timeoutMs must
// cover EVERY hop and every retry, not just one of them.
//
// Lives in jvmTest with runBlocking on purpose: these assertions are about real
// wall-clock budgeting, and a pending withTimeout under runTest gives the virtual
// scheduler a timer to fast-forward to — the call would expire before the engine
// ever ran (calls == 0), which tests nothing.
class PluginHttpClientBudgetTest {

    private val metadata = PluginMetadata(
        id = "dev.mirutime.demo",
        name = "Demo",
        baseUrl = "https://example.com",
        contentType = ContentType.NOVEL,
        language = "ru",
        api = 1,
        version = "0.1.0",
        sha256 = "abc",
        // 503 must not read as a CF challenge, or the detector throws instead of
        // returning the status the test asserts on.
        cfOptions = CfOptions(statuses = listOf(403, 429)),
    )

    private fun client(engine: MockEngine, defaultDeadlineMs: Long? = 90_000) = PluginHttpClient(
        metadata = metadata,
        systemInfo = FakeSystemInfo(),
        cookieStore = FakeCookieStore(),
        logger = RecordingLogger(),
        engine = engine,
        defaultDeadlineMs = defaultDeadlineMs,
    )

    // NoveLA BudgetedCall: a budgeted call makes exactly ONE attempt. Sleeping
    // 500ms between backoff retries would spend the budget on a failing host.
    @Test
    fun budgetedCallMakesExactlyOneStatusAttempt() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("boom", HttpStatusCode.ServiceUnavailable)
        }

        val response = client(engine).get("demo", "https://example.com/x", timeoutMs = 5_000)

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(1, calls)
    }

    // The budget spans redirect hops. Each hop alone fits inside 450ms, but the
    // two together must not: with a per-hop timeout the second hop would get its
    // own 450ms and the call would succeed, which is exactly the bug this fixes.
    @Test
    fun budgetSpansRedirectHops() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            delay(300)
            if (calls == 1) {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://example.com/next"),
                )
            } else {
                respond("done")
            }
        }

        assertFailsWith<HttpRequestTimeoutException> {
            client(engine).get("demo", "https://example.com/start", timeoutMs = 450)
        }

        assertEquals(2, calls)
    }

    // Bug fix: HttpRequestRetry's exception path used to ignore the budget flag,
    // so a budgeted call still bought 3 transport retries plus backoff sleeps —
    // a live socket error on a 1s budget would come back 4 attempts later.
    // NoveLA BudgetedCall: exactly ONE attempt, whatever fails.
    @Test
    fun budgetedCallDoesNotRetryTransportErrors() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            throw IOException("connection reset by peer")
        }

        assertFailsWith<IOException> {
            client(engine).get("demo", "https://example.com/x", timeoutMs = 5_000)
        }

        assertEquals(1, calls, "transport failures must not buy retries inside a budget")
    }

    // Bug fix: the exception axis used to sleep Ktor's full 1s/2s/4s backoff no
    // matter what the whole-call deadline could still pay for. Here the host
    // burns 350ms of a 500ms budget, so only ~150ms of backoff is affordable:
    // the retry must land INSIDE the budget instead of 1-2s after it.
    // (350, not 450: the verdict is a wall-clock race won by milliseconds, and
    // a loaded machine routinely contributes tens of ms of scheduling lag —
    // the old code failed this test the same way under load. Both pins below
    // are unchanged: the retry must still happen, and an unclamped 1s+ sleep
    // still blows the 1000ms ceiling.)
    @Test
    fun exceptionRetryBackoffIsClampedByTheDeadline() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                delay(350)
                throw IOException("connection reset by peer")
            }
            respond("recovered")
        }

        val started = System.nanoTime()
        val response = client(engine, defaultDeadlineMs = 500).get("demo", "https://example.com/x")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("recovered", response.bodyAsText())
        assertEquals(2, calls, "a legal retry inside the budget must still happen")
        // Unclamped: 350 + [1000..1999] = 1350..2349ms. Clamped: 350 + ~150 = ~500ms.
        assertTrue(elapsedMs < 1_000, "backoff slept past the deadline: ${elapsedMs}ms for a 500ms budget")
    }
}
