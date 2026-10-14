package dev.mirutime.core.integration

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginDescriptor
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.api.PluginRegistry
import dev.mirutime.core.api.PluginState
import dev.mirutime.core.engine.adapter.LuaPluginSourceFactory
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeFileSystem
import dev.mirutime.core.host.FakeKeyValueStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import dev.mirutime.core.network.HostOptionsRegistry
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// End-to-end smoke over the PUBLIC pipeline: PluginRegistry + factory + the
// LuaJ engine + the ktor network stack (MockEngine), exactly as a consumer
// wires it. Written as a single batch — it only assembles existing, already
// tested behavior (per the task ruling), each step individually observable.
class SmokeTest {

    private val catalogHtml = """
        <ul>
          <li><a href="/book/1">First Book</a></li>
          <li><a href="/book/2">Second Book</a></li>
        </ul>
    """.trimIndent()

    // The factory installs every engine binding (html/crypto/json/…), but the
    // smoke plugin sticks to plain Lua patterns — like ethalon plugins
    // pre-dating the html helpers — so the HTTP path stays the only effect.
    private val pluginScript = """
        function getCatalogList(index)
          local r = http_get("https://catalog.example/list?page=" .. index)
          if r.error then return { items = {}, hasNext = false } end
          local items = {}
          for url, title in r.body:gmatch('<a href="([^"]+)">([^<]+)</a>') do
            items[#items + 1] = { title = title, url = url }
          end
          return { items = items, hasNext = false }
        end
    """.trimIndent()

    @Test
    fun `installed lua plugin serves a catalog fetched over http`() {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = catalogHtml,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/html; charset=UTF-8"),
            )
        }

        val metadata = PluginMetadata(
            id = "smoke.catalog",
            name = "Smoke Catalog",
            baseUrl = "https://catalog.example",
            contentType = ContentType.NOVEL,
            language = "en",
            api = 1,
            version = "1.0.0",
            sha256 = "smoke",
        )
        // Shared instance: the registry registers per-host CF/UA here, the
        // factory must read the same registry — otherwise the registered
        // options are silently dead.
        val hostOptions = HostOptionsRegistry()
        val registry = PluginRegistry(
            sourceFactory = LuaPluginSourceFactory(
                systemInfo = FakeSystemInfo(),
                cookieStore = FakeCookieStore(),
                keyValueStore = FakeKeyValueStore(),
                logger = RecordingLogger(),
                engine = engine,
                fileSystem = FakeFileSystem(),
                hostOptions = hostOptions,
                // catalog.example is a fake host — skip real DNS in this test;
                // the default SSRF guard has a dedicated test in LuaPluginSourceTest.
                ssrfGuard = null,
            ),
            hostOptions = hostOptions,
        )

        val handle = registry.install(PluginDescriptor(metadata, pluginScript))
        assertEquals(PluginState.Loaded, handle.state.value)
        val source = handle.source!!

        val page = runBlocking { source.catalog(0) }
        val ok = assertIs<Outcome.Ok<dev.mirutime.core.api.CatalogPage>>(page).value
        assertEquals(listOf("First Book", "Second Book"), ok.items.map { it.title })
        assertEquals(listOf("/book/1", "/book/2"), ok.items.map { it.url })
        assertTrue(!ok.hasNext)

        // The catalog really went through the HTTP stack to the mock engine.
        assertEquals(1, requests.size)
        assertEquals(
            "https://catalog.example/list?page=0",
            requests.single().url.toString(),
        )

        handle.dispose()
    }
}
