package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

// ContentEncoding wiring: gzip/br bodies decode transparently, and all supported
// encoders (gzip, deflate, br) are advertised in Accept-Encoding.
class PluginHttpClientCompressionTest {

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

    private fun client(engine: HttpClientEngine): PluginHttpClient = PluginHttpClient(
        metadata = metadata,
        systemInfo = FakeSystemInfo(),
        cookieStore = FakeCookieStore(),
        logger = RecordingLogger(),
        engine = engine,
    )

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.encodeToByteArray()) }
        return out.toByteArray()
    }

    @Test
    fun `gzip response body is transparently decoded`() {
        val payload = "compressed-body-payload"
        val engine = MockEngine { _ ->
            respond(
                content = gzip(payload),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("text/plain"),
                    HttpHeaders.ContentEncoding to listOf("gzip"),
                ),
            )
        }

        val body = runBlocking { client(engine).get("demo", "https://example.com/x").bodyAsText() }

        assertEquals(payload, body)
    }

    @Test
    fun `accept-encoding matches the registered encoder list`() {
        var acceptEncoding: String? = null
        val engine = MockEngine { request ->
            acceptEncoding = request.headers[HttpHeaders.AcceptEncoding]
            respond("ok")
        }

        runBlocking { client(engine).get("demo", "https://example.com/x") }

        // NoveLA parity: the exact wire value must not drift.
        assertEquals("gzip, deflate, br", acceptEncoding)
        // ...and it must be exactly the encoder set PluginHttpClient registers,
        // so advertising an undecodable encoding (or dropping a decodable one)
        // is impossible without failing here.
        assertEquals(SUPPORTED_CONTENT_ENCODINGS.joinToString(", "), acceptEncoding)
    }
}