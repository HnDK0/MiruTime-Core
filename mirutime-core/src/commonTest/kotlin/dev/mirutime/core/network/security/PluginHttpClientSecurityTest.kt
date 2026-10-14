package dev.mirutime.core.network.security

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import dev.mirutime.core.network.PluginHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginHttpClientSecurityTest {

    private val systemInfo = FakeSystemInfo()
    private val cookieStore = FakeCookieStore()
    private val logger = RecordingLogger()

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
        guard: SsrfGuard? = null,
        maxResponseBytes: Long = 25L * 1024 * 1024,
    ) = PluginHttpClient(
        metadata = metadata,
        systemInfo = systemInfo,
        cookieStore = cookieStore,
        logger = logger,
        engine = engine,
        ssrfGuard = guard,
        maxResponseBytes = maxResponseBytes,
    )

    @Test
    fun blockedUrlFailsBeforeAnyRequest() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            respond("ok")
        }
        val guard = SsrfGuard { listOf("127.0.0.1") }

        val error = assertFailsWith<SsrfBlockedException> {
            client(engine, guard).get("demo", "https://internal.example.com/page")
        }
        assertEquals(0, engineCalls, "request must not reach the engine")
        assertEquals(true, error.reason.isNotEmpty())
    }

    @Test
    fun dnsFailureSurfacesAsException() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            respond("ok")
        }
        val guard = SsrfGuard { emptyList() }

        assertFailsWith<DnsFailureException> {
            client(engine, guard).get("demo", "https://ghost.example.com/page")
        }
        assertEquals(0, engineCalls)
    }

    @Test
    fun nullGuardSkipsCheck() = runTest {
        val engine = MockEngine { respond("ok content") }

        val response = client(engine).get("demo", "https://example.com/page")

        assertEquals(200, response.status.value)
        assertEquals("ok content", response.bodyAsText())
    }

    @Test
    fun oversizedBodyFailsFast() = runTest {
        val engine = MockEngine { respond(ByteArray(6 * 1024 * 1024)) }

        val error = assertFailsWith<ResponseTooLargeException> {
            client(engine, maxResponseBytes = 5L * 1024 * 1024)
                .get("demo", "https://example.com/big")
        }
        assertEquals(5L * 1024 * 1024, error.error.limit)
    }

    @Test
    fun redirectsAreFollowedByDefault() = runTest {
        // NoveLA parity: followRedirects defaults to true.
        var engineCalls = 0
        val engine = MockEngine { request ->
            engineCalls++
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

        val response = client(engine).get("demo", "https://example.com/302")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("final body", response.bodyAsText())
        assertEquals(2, engineCalls)
    }

    @Test
    fun redirectsCanBeDisabledPerRequest() = runTest {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://example.com/final"),
            )
        }

        val response = client(engine).get("demo", "https://example.com/302", followRedirects = false)

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("https://example.com/final", response.headers[HttpHeaders.Location])
        assertEquals(1, engineCalls)
    }

    // NoveLA's engine (OkHttp) PRESERVES HEAD on every redirect status. The
    // 3xx downgrade must therefore only touch methods that actually carry a
    // body — a HEAD that got flipped to GET would send a body-less request
    // under the wrong method and confuse origin-side logging/caching.
    @Test
    fun headIsPreservedAcrossA303Redirect() = runTest {
        val methods = mutableListOf<String>()
        val engine = MockEngine { request ->
            methods += request.method.value
            if (request.url.encodedPath == "/303") {
                respond(
                    content = "",
                    status = HttpStatusCode.SeeOther,
                    headers = headersOf(HttpHeaders.Location, "https://example.com/final"),
                )
            } else {
                respond("final")
            }
        }

        client(engine).get("demo", "https://example.com/303", method = "HEAD")

        assertEquals(listOf("HEAD", "HEAD"), methods, "HEAD must survive a 303 downgrade")
    }

    // A body-carrying POST does degrade to GET on 303 (browser behaviour), and
    // the entity must not ride along on the body-less follow-up.
    @Test
    fun postDegradesToGetOnA303Redirect() = runTest {
        // MockEngine sees Content-Type on the OutgoingContent, not in
        // request.headers, so the body type is what proves the drop.
        val requests = mutableListOf<Pair<String, Boolean>>()
        val engine = MockEngine { request ->
            requests += request.method.value to (request.body is TextContent)
            if (request.url.encodedPath == "/303") {
                respond(
                    content = "",
                    status = HttpStatusCode.SeeOther,
                    headers = headersOf(HttpHeaders.Location, "https://example.com/final"),
                )
            } else {
                respond("final")
            }
        }

        client(engine).post(
            sourceId = "demo",
            url = "https://example.com/303",
            body = "payload",
            contentType = "text/plain",
        )

        assertEquals(listOf("POST" to true, "GET" to false), requests)
    }

    @Test
    fun redirectToBlockedHostIsRefused() = runTest {
        // A 302 to a private/metadata host must not bypass the SSRF guard:
        // every hop is re-validated before the follow-up request is sent.
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(
                    HttpHeaders.Location,
                    "https://metadata.internal/latest/meta-data/",
                ),
            )
        }
        val guard = SsrfGuard { host ->
            if (host == "metadata.internal") listOf("169.254.169.254") else listOf("93.184.216.34")
        }

        assertFailsWith<SsrfBlockedException> {
            client(engine, guard).get("demo", "https://attacker.example/302")
        }
        assertEquals(1, engineCalls, "must not send the request to a blocked redirect target")
    }
}
