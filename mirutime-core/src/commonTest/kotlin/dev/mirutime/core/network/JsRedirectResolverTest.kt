package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Port of NoveLA's JsRedirectResolverTest (jsoup Document replaced by a
// raw-HTML + base-URL pair — commonMain has no HTML parser).
class JsRedirectResolverTest {

    @Test
    fun `html entity in redirect url is decoded`() {
        val html = """
            <html><body><script>
            window.location.href = '/login-email?next&#x3D;%2Fnovel%2Ffoo%2Fchapter-1';
            </script></body></html>
        """.trimIndent()

        val url = JsRedirectResolver.resolveRedirectUrl(html, "https://example.com/novel/foo/chapter-1")

        assertNotNull(url)
        assertEquals("https://example.com/login-email?next=%2Fnovel%2Ffoo%2Fchapter-1", url)
    }

    @Test
    fun `plain absolute js redirect is resolved`() {
        val html = """
            <html><body><script>
            window.location.href = "https://other.com/real";
            </script></body></html>
        """.trimIndent()

        assertEquals(
            "https://other.com/real",
            JsRedirectResolver.resolveRedirectUrl(html, "https://example.com/loading"),
        )
    }

    @Test
    fun `no redirect returns null`() {
        assertNull(
            JsRedirectResolver.resolveRedirectUrl(
                """<html><body><p>just text</p></body></html>""",
                "https://example.com/chapter-1",
            ),
        )
    }

    @Test
    fun `meta refresh with relative url is resolved`() {
        val html = """
            <html><head>
            <meta http-equiv="refresh" content="0;url=/real-page">
            </head><body></body></html>
        """.trimIndent()

        assertEquals(
            "https://example.com/real-page",
            JsRedirectResolver.resolveRedirectUrl(html, "https://example.com/novel/foo"),
        )
    }

    @Test
    fun `location assign form is resolved`() {
        val html = """
            <html><body><script>
            location.assign('https://other.com/real');
            </script></body></html>
        """.trimIndent()

        assertEquals(
            "https://other.com/real",
            JsRedirectResolver.resolveRedirectUrl(html, "https://example.com/loading"),
        )
    }

    @Test
    fun `self location form is resolved`() {
        val html = """
            <html><body><script>
            self.location = 'https://other.com/real';
            </script></body></html>
        """.trimIndent()

        assertEquals(
            "https://other.com/real",
            JsRedirectResolver.resolveRedirectUrl(html, "https://example.com/loading"),
        )
    }

    @Test
    fun `javascript target is skipped in meta refresh`() {
        val html = """<html><head><meta http-equiv="refresh" content="0;url=javascript:void(0)"></head></html>"""
        assertNull(JsRedirectResolver.resolveRedirectUrl(html, "https://example.com/x"))
    }

    // ── redirect-flow wiring (RedirectFollower) ──────────────────────────────

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

    private fun htmlPage() = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")

    @Test
    fun `meta refresh hop is followed with referer`() = runTest {
        var calls = 0
        var hopReferer: String? = null
        val engine = MockEngine { request ->
            calls++
            if (request.url.encodedPath == "/landing") {
                respond(
                    """<html><head><meta http-equiv="refresh" content="0;url=/real"></head></html>""",
                    HttpStatusCode.OK, htmlPage(),
                )
            } else {
                hopReferer = request.headers["Referer"]
                respond("final", HttpStatusCode.OK, htmlPage())
            }
        }

        val response = client(engine).get("demo", "https://example.com/landing")

        assertEquals("final", response.bodyAsText())
        assertEquals(2, calls)
        // NoveLA sets Referer: realUrl on the manual JS hop.
        assertEquals("https://example.com/landing", hopReferer)
    }

    @Test
    fun `js location hop is followed`() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            if (request.url.encodedPath == "/loading") {
                respond(
                    """<html><body><script>window.location.href = "https://example.com/real";</script></body></html>""",
                    HttpStatusCode.OK, htmlPage(),
                )
            } else {
                respond("final", HttpStatusCode.OK, htmlPage())
            }
        }

        val response = client(engine).get("demo", "https://example.com/loading")

        assertEquals("final", response.bodyAsText())
        assertEquals(2, calls)
    }

    @Test
    fun `non-html body is never js-scanned`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("""window.location.href = "https://example.com/real";""")
        }

        val response = client(engine).get("demo", "https://example.com/loading")

        assertEquals(1, calls)
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `followRedirects false skips js hops`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(
                """<html><head><meta http-equiv="refresh" content="0;url=/real"></head></html>""",
                HttpStatusCode.OK, htmlPage(),
            )
        }

        client(engine).get("demo", "https://example.com/landing", followRedirects = false)

        assertEquals(1, calls)
    }
}
