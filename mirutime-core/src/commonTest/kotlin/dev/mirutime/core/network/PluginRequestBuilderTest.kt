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
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.ByteReadChannel
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginRequestBuilderTest {

    private val systemInfo = FakeSystemInfo(
        defaultUserAgent = "MiruCore/1.0",
        acceptLanguage = "ru-RU",
    )
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

    private fun clientWith(
        userAgentPreset: String?,
        hostOptions: HostOptionsRegistry? = null,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): PluginHttpClient = PluginHttpClient(
        metadata = metadata.copy(userAgentPreset = userAgentPreset),
        systemInfo = systemInfo,
        cookieStore = cookieStore,
        logger = logger,
        engine = MockEngine(handler),
        hostOptions = hostOptions,
    )

    @Test
    fun acceptLanguageCarriesSystemInfoValue() = runTest {
        var captured: String? = null
        val client = clientWith(null) { request ->
            captured = request.headers[HttpHeaders.AcceptLanguage]
            respond("ok")
        }

        client.get("demo", "https://example.com/page")

        assertEquals("ru-RU", captured)
    }

    @Test
    fun userAgentUsesPluginPresetWhenSet() = runTest {
        var captured: String? = null
        val client = clientWith("PresetUA/2.0") { request ->
            captured = request.headers[HttpHeaders.UserAgent]
            respond("ok")
        }

        client.get("demo", "https://example.com/page")

        assertEquals("PresetUA/2.0", captured)
    }

    @Test
    fun userAgentFallsBackToBrowserDefault() = runTest {
        PluginUserAgentRegistry.clear()
        var captured: String? = null
        val client = clientWith(null) { request ->
            captured = request.headers[HttpHeaders.UserAgent]
            respond("ok")
        }

        client.get("demo", "https://example.com/page")

        assertEquals(UserAgentPresets.DEFAULT, captured)
    }

    @Test
    fun cookiesForwardedAndSetCookieWrittenBack() = runTest {
        cookieStore.setFromResponse("https://example.com/", listOf("session=abc; Path=/"))
        var cookieHeader: String? = null
        val client = clientWith(null) { request ->
            cookieHeader = request.headers[HttpHeaders.Cookie]
            respond("ok", headers = headersOf(HttpHeaders.SetCookie, "token=xyz; Path=/"))
        }

        client.get("demo", "https://example.com/page")

        assertEquals("session=abc", cookieHeader)
        val stored = cookieStore.cookies.first { it.name == "token" }
        assertEquals("xyz", stored.value)
        assertEquals("example.com", stored.domain)
    }

    @Test
    fun extraHeadersOverrideDefaultsWithoutDuplicates() = runTest {
        var userAgents: List<String?> = emptyList()
        var acceptLanguages: List<String?> = emptyList()
        cookieStore.setFromResponse("https://example.com/", listOf("session=abc; Path=/"))
        var cookieHeaders: List<String?> = emptyList()
        val client = clientWith("PresetUA/2.0") { request ->
            userAgents = request.headers.getAll(HttpHeaders.UserAgent).orEmpty()
            acceptLanguages = request.headers.getAll(HttpHeaders.AcceptLanguage).orEmpty()
            cookieHeaders = request.headers.getAll(HttpHeaders.Cookie).orEmpty()
            respond("ok")
        }

        client.get(
            "demo",
            "https://example.com/page",
            headers = mapOf(
                HttpHeaders.UserAgent to "CallerUA/9.9",
                HttpHeaders.AcceptLanguage to "de-DE",
                HttpHeaders.Cookie to "caller=1",
            ),
        )

        assertEquals(listOf("CallerUA/9.9"), userAgents)
        assertEquals(listOf("de-DE"), acceptLanguages)
        assertEquals(listOf("caller=1"), cookieHeaders)
    }

    @Test
    fun timeoutMsAbortsSlowRequest() = runTest {
        val client = clientWith(null) {
            delay(500)
            respond("late")
        }

        assertFailsWith<HttpRequestTimeoutException> {
            client.get("demo", "https://example.com/slow", timeoutMs = 50)
        }
    }

    @Test
    fun gzipEncodedResponseIsTransparentlyDecoded() = runTest {
        val original = """{"status":"ok","data":"hello world"}"""
        val compressed = compressGzip(original)
        var capturedBody: String? = null
        val client = clientWith(null) { request ->
            // Verify client sends gzip+deflate+br.
            val acceptEnc = request.headers[HttpHeaders.AcceptEncoding]
            assertTrue(acceptEnc?.contains("gzip") == true)
            assertTrue(acceptEnc?.contains("deflate") == true)
            assertTrue(acceptEnc?.contains("br") == true)
            respond(content = compressed, headers = headersOf(HttpHeaders.ContentEncoding, "gzip"))
        }

        val response = client.get("demo", "https://example.com/api")
        capturedBody = response.bodyAsText()

        assertEquals(original, capturedBody)
    }

    // pre-compressed fixture: {"status":"ok","data":"hello world"}
    // org.brotli:dec is decode-only, so the bytes are produced offline and
    // checked in — nothing compresses at test time and no JNI is involved.
    private val brotliFixture = Base64.getDecoder().decode(
        "oRgBwG+kFmvOcKcWWXvbmzosfTCRA4fAWnaydwUmwGCOjsMShyPS",
    )
    private val brotliFixtureText = """{"status":"ok","data":"hello world"}"""

    @Test
    fun brEncodedResponseIsTransparentlyDecoded() = runTest {
        var capturedBody: String? = null
        val client = clientWith(null) { request ->
            val acceptEnc = request.headers[HttpHeaders.AcceptEncoding]
            assertTrue(acceptEnc?.contains("br") == true)
            respond(content = brotliFixture, headers = headersOf(HttpHeaders.ContentEncoding, "br"))
        }

        val response = client.get("demo", "https://example.com/api")
        capturedBody = response.bodyAsText()

        assertEquals(brotliFixtureText, capturedBody)
    }

    @Test
    fun brotliFixtureIsDecodableByOrgBrotli() {
        // Independent of the client: proves the checked-in bytes really are
        // valid brotli for org.brotli:dec before they reach any Ktor wiring.
        val decoded = BrotliInputStream(brotliFixture.inputStream()).use { it.readBytes() }
        assertEquals(brotliFixtureText, String(decoded, Charsets.UTF_8))
    }

    // The CF retry path overrides the UA with the solver's browser — the
    // sec-ch-ua* family must follow THAT UA, not the plugin's mobile preset
    // (the fictionzone bug: macOS UA + sec-ch-ua-platform "Android" + mobile ?1
    // is an obvious bot signal).
    @Test
    fun solverUserAgentRecomputesClientHints() = runTest {
        var platform: String? = null
        var mobile: String? = null
        var engineCalls = 0
        val engine = MockEngine { request ->
            engineCalls++
            if (engineCalls == 1) {
                respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
            } else {
                platform = request.headers["sec-ch-ua-platform"]
                mobile = request.headers["sec-ch-ua-mobile"]
                respond("ok")
            }
        }
        val solver = object : CfChallengeSolver {
            override suspend fun solve(request: CfChallenge) = CfSolution(
                mapOf("cf_clearance" to "token-1"),
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36",
            )
        }
        val client = PluginHttpClient(
            metadata = metadata.copy(userAgentPreset = UserAgentPresets.CHROME_150_ANDROID),
            systemInfo = systemInfo,
            cookieStore = cookieStore,
            logger = logger,
            engine = engine,
            solver = solver,
        )

        client.get("demo", "https://example.com/page")

        assertEquals("\"macOS\"", platform)
        assertEquals("?0", mobile)
    }

    private fun compressGzip(input: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(input.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    // ── per-host referer (NoveLA SourceInterface.referer) ─────────────────────
    // The engine registers the plugin's `referer` global per host at load;
    // the request carries it when the caller sent no explicit Referer.

    @Test
    fun registryRefererIsSentWhenCallerSendsNone() = runTest {
        val options = HostOptionsRegistry()
        options.setReferer("example.com", "plugin.a", "https://covers.example/")
        var captured: String? = null
        val client = clientWith(null, options) { request ->
            captured = request.headers["Referer"]
            respond("ok")
        }

        client.get("demo", "https://example.com/page")

        assertEquals("https://covers.example/", captured)
    }

    @Test
    fun explicitCallerRefererWinsOverRegistry() = runTest {
        val options = HostOptionsRegistry()
        options.setReferer("example.com", "plugin.a", "https://covers.example/")
        var captured: String? = null
        val client = clientWith(null, options) { request ->
            captured = request.headers["Referer"]
            respond("ok")
        }

        client.get("demo", "https://example.com/page", headers = mapOf("Referer" to "https://caller.example/b"))

        assertEquals("https://caller.example/b", captured)
    }

    @Test
    fun clearedRegistryRefererFallsBackToSchemeHostDefault() = runTest {
        val options = HostOptionsRegistry()
        options.setReferer("example.com", "plugin.a", "https://covers.example/")
        // dispose(): only the owner's entry is removed.
        options.clear("example.com", "plugin.a")
        var captured: String? = null
        val client = clientWith(null, options) { request ->
            captured = request.headers["Referer"]
            respond("ok")
        }

        client.get("demo", "https://example.com/page")

        assertEquals("https://example.com/", captured)
    }
}
