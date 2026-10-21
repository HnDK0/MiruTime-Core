package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import dev.mirutime.core.network.cf.CfOptions
import dev.mirutime.core.network.security.SsrfBlockedException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Transport resilience: which failures earn another attempt, and which must
// surface immediately. NoveLA retried IOException under a GET-only budget.
class PluginHttpClientResilienceTest {

    private val metadata = PluginMetadata(
        id = "dev.mirutime.demo",
        name = "Demo",
        baseUrl = "https://example.com",
        contentType = ContentType.NOVEL,
        language = "ru",
        api = 1,
        version = "0.1.0",
        sha256 = "abc",
        // 503 is a challenge by default — keep it out so these tests observe
        // retry behaviour instead of the CF detector taking over.
        cfOptions = CfOptions(statuses = listOf(403, 429)),
    )

    private fun client(engine: MockEngine) = PluginHttpClient(
        metadata = metadata,
        systemInfo = FakeSystemInfo(),
        cookieStore = FakeCookieStore(),
        logger = RecordingLogger(),
        engine = engine,
    )

    // A dropped connection is transient and must be retried. Before
    // HttpRequestRetry was installed this escaped to the caller on attempt 1.
    @Test
    fun transientIOExceptionIsRetriedForGet() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) throw IOException("connection reset by peer")
            respond("ok")
        }

        val response = client(engine).get("demo", "https://example.com/x")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
        assertEquals(2, calls)
    }

    // ceiling: commonMain cannot name java.io.IOException, so the predicate is
    // inverted — retry UNLESS the failure is one of our own domain/timeout/
    // cancellation errors. Those must surface on the first attempt.
    @Test
    fun domainExceptionIsNotRetried() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            throw SsrfBlockedException("private address")
        }

        assertFailsWith<SsrfBlockedException> {
            client(engine).get("demo", "https://example.com/x")
        }

        assertEquals(1, calls)
    }

    // NoveLA ladder: 5 attempts total, then the last error surfaces.
    @Test
    fun persistentTransportFailureFailsAfterFiveAttempts() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            throw IOException("connection reset by peer")
        }

        assertFailsWith<IOException> {
            client(engine).get("demo", "https://example.com/x")
        }

        assertEquals(5, calls)
    }

    // Only idempotent GETs ladder — a POST body may not be replayable.
    @Test
    fun postIsNeverRetried() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            throw IOException("connection reset by peer")
        }

        assertFailsWith<IOException> {
            client(engine).post("demo", "https://example.com/x", "body")
        }

        assertEquals(1, calls)
    }

    // NoveLA BudgetedCall: an explicit timeoutMs means exactly ONE attempt —
    // retries must not multiply the caller's budget.
    // runBlocking, not runTest: a pending withTimeout under runTest gives the
    // virtual scheduler a timer to fast-forward to, so the call can expire
    // before the engine ever runs (calls == 0) on a loaded CI machine.
    @Test
    fun budgetedCallIsSingleAttempt() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            throw IOException("connection reset by peer")
        }

        assertFailsWith<IOException> {
            client(engine).get("demo", "https://example.com/x", timeoutMs = 5_000)
        }

        assertEquals(1, calls)
    }
}
