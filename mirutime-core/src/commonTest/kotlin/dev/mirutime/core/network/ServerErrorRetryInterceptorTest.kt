package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import dev.mirutime.core.network.cf.CfOptions
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerErrorRetryInterceptorTest {

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

    private fun client(engine: MockEngine) = PluginHttpClient(
        metadata = metadata,
        systemInfo = FakeSystemInfo(),
        cookieStore = FakeCookieStore(),
        logger = RecordingLogger(),
        engine = engine,
    )

    @Test
    fun transientServerErrorIsRetriedUntilSuccess() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls < 3) respond("boom", HttpStatusCode.ServiceUnavailable) else respond("ok")
        }

        val response = client(engine).get("demo", "https://example.com/x")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
        assertEquals(3, calls)
    }

    @Test
    fun rateLimitIsRetried() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) respond("slow down", HttpStatusCode.TooManyRequests) else respond("ok")
        }

        val response = client(engine).get("demo", "https://example.com/x")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, calls)
    }

    @Test
    fun clientErrorIsNotRetried() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("nope", HttpStatusCode.NotFound)
        }

        val response = client(engine).get("demo", "https://example.com/x")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(1, calls)
    }

    // The counterpart to the CF short-circuit: only a *challenge-shaped* 503 is
    // held back. With 503 removed from CfOptions.statuses this is an ordinary
    // transient error, so the normal backoff must still run to exhaustion.
    @Test
    fun plainServerError503StillRetries() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("boom", HttpStatusCode.ServiceUnavailable)
        }
        val plainClient = PluginHttpClient(
            metadata = metadata.copy(cfOptions = CfOptions(statuses = listOf(403, 429))),
            systemInfo = FakeSystemInfo(),
            cookieStore = FakeCookieStore(),
            logger = RecordingLogger(),
            engine = engine,
        )

        val response = plainClient.get("demo", "https://example.com/x")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(5, calls) // 1 initial attempt + 4 backoff retries
    }
}
