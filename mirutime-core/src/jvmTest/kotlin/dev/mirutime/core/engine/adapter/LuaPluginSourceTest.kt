package dev.mirutime.core.engine.adapter

import dev.mirutime.core.api.ChapterContent
import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginDescriptor
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.api.PluginRegistry
import dev.mirutime.core.api.PluginSource
import dev.mirutime.core.engine.createLuaSession
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeFileSystem
import dev.mirutime.core.host.FakeKeyValueStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import dev.mirutime.core.network.HostOptionsRegistry
import dev.mirutime.core.network.PluginUserAgentRegistry
import dev.mirutime.core.network.UserAgentPresets
import dev.mirutime.core.network.cache.ResponseCache
import dev.mirutime.core.network.cache.sha256Hex
import dev.mirutime.core.network.cf.CfOptions
import dev.mirutime.core.network.cf.CloudflareDetector
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.luaj.vm2.LuaValue
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LuaPluginSourceTest {

    private val mockEngine = MockEngine { _ ->
        respond(
            content = "remote:<p>from-http</p>",
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Type", "text/html; charset=UTF-8"),
        )
    }
    private val hostOptions = HostOptionsRegistry()
    private val fileSystem = FakeFileSystem()

    private fun factory(
        engine: io.ktor.client.engine.HttpClientEngine = mockEngine,
        options: HostOptionsRegistry? = null,
        cache: ResponseCache? = null,
    ) = LuaPluginSourceFactory(
        systemInfo = FakeSystemInfo(),
        cookieStore = FakeCookieStore(),
        keyValueStore = FakeKeyValueStore(),
        logger = RecordingLogger(),
        engine = engine,
        fileSystem = fileSystem,
        hostOptions = options,
        // Fake test hosts (site.example) do not resolve — skip real DNS here;
        // the default guard has its own dedicated test below.
        ssrfGuard = null,
        cache = cache,
    )

    private fun metadata(
        id: String = "test.plugin",
        cf: CfOptions? = null,
        ua: String? = null,
    ) = PluginMetadata(
        id = id,
        name = "Test",
        baseUrl = "https://site.example",
        contentType = ContentType.NOVEL,
        language = "en",
        api = 1,
        version = "0.1.0",
        sha256 = "abc",
        userAgentPreset = ua,
        cfOptions = cf,
    )

    private fun source(script: String, meta: PluginMetadata = metadata()): PluginSource =
        factory().create(PluginDescriptor(meta, script))

    private fun <T> Outcome<T>.ok(): T = (this as Outcome.Ok).value
    private fun <T> Outcome<T>.err(): dev.mirutime.core.api.MiruError =
        (this as Outcome.Err).error

    // ── chapters: adapter never reorders ─────────────────────────────────────

    @Test
    fun `chapter list keeps plugin order without sorting`() {
        val s = source(
            """
            function getChapterList(url)
              return {
                { title = "Ch 3", url = "u3" },
                { title = "Ch 1", url = "u1" },
                { title = "Ch 2", url = "u2" },
              }
            end
            """.trimIndent(),
        )
        val chapters = runBlocking { s.chapters("https://site.example/book/1") }.ok()
        // The plugin returned new-first; the adapter must not "fix" it.
        assertEquals(listOf("Ch 3", "Ch 1", "Ch 2"), chapters.map { it.title })
        assertEquals(listOf("u3", "u1", "u2"), chapters.map { it.url })
    }

    // ── chapterContent dispatch: Video > Pages > Text ────────────────────────

    @Test
    fun `novel plugin returns text content`() {
        val s = source(
            """
            function getChapterText(html, url) return "<p>chapter body</p>" end
            """.trimIndent(),
        )
        val content = runBlocking { s.chapterContent("<div/>", "https://site.example/c/1") }.ok()
        assertEquals(ChapterContent.Text("<p>chapter body</p>"), content)
    }

    @Test
    fun `manga plugin returns pages`() {
        val s = source(
            """
            function getPageList(html, url) return { "https://i.example/1.png", "https://i.example/2.png" } end
            """.trimIndent(),
        )
        val content = runBlocking { s.chapterContent("<div/>", "https://site.example/c/1") }.ok()
        assertEquals(
            ChapterContent.Pages(listOf("https://i.example/1.png", "https://i.example/2.png")),
            content,
        )
    }

    @Test
    fun `video plugin returns streams`() {
        val s = source(
            """
            function getVideoList(url)
              return { { url = "https://v.example/1.m3u8", quality = "1080p", mime = "application/x-mpegURL" } }
            end
            """.trimIndent(),
        )
        val content = runBlocking { s.chapterContent("", "https://site.example/ep/1") }.ok()
        val video = assertIs<ChapterContent.Video>(content)
        assertEquals("https://v.example/1.m3u8", video.streams.single().url)
        assertEquals("1080p", video.streams.single().quality)
        assertEquals("application/x-mpegURL", video.streams.single().mime)
    }

    @Test
    fun `pages take priority over text, video over pages`() {
        val s = source(
            """
            function getChapterText(html, url) return "text" end
            function getPageList(html, url) return { "p1" } end
            """.trimIndent(),
        )
        assertEquals(ChapterContent.Pages(listOf("p1")), runBlocking { s.chapterContent("", "u") }.ok())

        val video = source(
            """
            function getPageList(html, url) return { "p1" } end
            function getVideoList(url) return { { url = "v1" } } end
            """.trimIndent(),
        )
        assertEquals(
            ChapterContent.Video(listOf(dev.mirutime.core.api.VideoStream(url = "v1"))),
            runBlocking { video.chapterContent("", "u") }.ok(),
        )
    }

    @Test
    fun `plugin with no content functions fails with ScriptError`() {
        val s = source("x = 1")
        val result = runBlocking { s.chapterContent("<div/>", "u") }
        assertTrue(result.err() is dev.mirutime.core.api.PluginError.ScriptError)
    }

    // ── catalog / search / book ──────────────────────────────────────────────

    @Test
    fun `catalog pagination passes items and hasNext through`() {
        val s = source(
            """
            function getCatalogList(index)
              return {
                items = {
                  { title = "Book " .. index, url = "b" .. index, cover = "c", rating = 4.6 },
                },
                hasNext = true,
              }
            end
            """.trimIndent(),
        )
        val page = runBlocking { s.catalog(2) }.ok()
        assertTrue(page.hasNext)
        assertEquals("Book 2", page.items.single().title)
        assertEquals("b2", page.items.single().url)
        assertEquals("c", page.items.single().coverUrl)
        assertEquals("4.6", page.items.single().rating)
    }

    @Test
    fun `search forwards the query and returns an outcome`() {
        val s = source(
            """
            function getCatalogSearch(index, query)
              return { items = { { title = "found:" .. query, url = "u" } }, hasNext = false }
            end
            """.trimIndent(),
        )
        val page = runBlocking { s.search(0, "tower") }.ok()
        assertEquals("found:tower", page.items.single().title)
    }

    @Test
    fun `book meta converts all fields`() {
        val s = source(
            """
            function getBookMeta(url)
              return {
                title = "The Book",
                cover = "https://img.example/c.jpg",
                description = "desc",
                genres = { "Fantasy", "Action" },
                rating = 4.2,
                status = "ongoing",
              }
            end
            """.trimIndent(),
        )
        val book = runBlocking { s.book("https://site.example/book/1") }.ok()
        assertEquals("The Book", book.title)
        assertEquals("https://img.example/c.jpg", book.coverUrl)
        assertEquals("desc", book.description)
        assertEquals(listOf("Fantasy", "Action"), book.genres)
        assertEquals("4.2", book.rating)
        assertEquals("ongoing", book.status)
    }

    // Optional scalars (rating/status/lastUpdated): nil means "absent" and must
    // not abort the whole book() call (aniliberty getBookRating returns nil for
    // unrated titles). A real Lua error() still propagates.
    @Test
    fun `nil book rating does not abort book`() {
        val s = source(
            """
            function getBookTitle(url) return "Unrated" end
            function getBookRating(url) return nil end
            function getBookStatus(url) return "ongoing" end
            """.trimIndent(),
        )
        val book = runBlocking { s.book("https://site.example/book/1") }.ok()
        assertEquals("Unrated", book.title)
        assertNull(book.rating)
        assertEquals("ongoing", book.status)
    }

    @Test
    fun `lua error in book rating still fails book`() {
        val s = source(
            """
            function getBookTitle(url) return "T" end
            function getBookRating(url) error("boom") end
            """.trimIndent(),
        )
        val result = runBlocking { s.book("https://site.example/book/1") }
        assertIs<dev.mirutime.core.api.PluginError>(result.err())
    }

    @Test
    fun `missing lua function surfaces ScriptError from the session`() {
        val s = source("x = 1")
        val result = runBlocking { s.catalog(0) }
        assertTrue(result.err() is dev.mirutime.core.api.PluginError.ScriptError)
    }

    // ── book extras: BookMeta.extras (arbitrary metadata) ────────────────────

    @Test
    fun `legacy getBookMeta unknown keys land in extras`() {
        val s = source(
            """
            function getBookMeta(url)
              return {
                title = "The Book",
                cover = "https://img.example/c.jpg",
                genres = { "Fantasy" },
                status = "ongoing",
                tags = "magic, towers",
                chapters = 42,
                translator = { "not", "a", "scalar" },
              }
            end
            """.trimIndent(),
        )
        val book = runBlocking { s.book("https://site.example/book/1") }.ok()
        // Canonical fields stay canonical, never mirrored into extras...
        assertEquals("The Book", book.title)
        assertEquals(mapOf("tags" to "magic, towers", "chapters" to "42"), book.extras)
    }

    @Test
    fun `getBookExtras table lands in extras on the per-field path`() {
        val s = source(
            """
            function getBookTitle(url) return "T" end
            function getBookExtras(url)
              return { translator = "X", altTitle = "Alt", count = 17 }
            end
            """.trimIndent(),
        )
        val book = runBlocking { s.book("https://site.example/book/1") }.ok()
        assertEquals(mapOf("translator" to "X", "altTitle" to "Alt", "count" to "17"), book.extras)
    }

    @Test
    fun `extras never override canonical fields`() {
        val s = source(
            """
            function getBookTitle(url) return "Canonical" end
            function getBookExtras(url)
              return { title = "Bogus", cover = "bogus.jpg", tag = "t" }
            end
            """.trimIndent(),
        )
        val book = runBlocking { s.book("https://site.example/book/1") }.ok()
        assertEquals("Canonical", book.title)
        // Known keys are filtered out of the extras source, not just shadowed.
        assertEquals(mapOf("tag" to "t"), book.extras)
    }

    @Test
    fun `non-scalar extras values are skipped, not fatal`() {
        val s = source(
            """
            function getBookTitle(url) return "T" end
            function getBookExtras(url)
              return { ok = "yes", nested = { deep = 1 }, list = { "a", "b" }, flag = true }
            end
            """.trimIndent(),
        )
        val book = runBlocking { s.book("https://site.example/book/1") }.ok()
        assertEquals(mapOf("ok" to "yes"), book.extras)
    }

    @Test
    fun `book without any extras source has empty extras`() {
        val s = source(
            """
            function getBookTitle(url) return "T" end
            """.trimIndent(),
        )
        val book = runBlocking { s.book("https://site.example/book/1") }.ok()
        assertEquals(emptyMap(), book.extras)
    }

    @Test
    fun `legacy getBookMeta error still fails book`() {
        val s = source(
            """
            function getBookMeta(url) error("meta boom") end
            """.trimIndent(),
        )
        val result = runBlocking { s.book("https://site.example/book/1") }
        assertIs<dev.mirutime.core.api.PluginError>(result.err())
    }

    // ── book(): GET memo (same-URL http_get dedup within one call) ───────────

    @Test
    fun `book getters fetching the same URL hit the engine once`() {
        val engine = MockEngine { _ ->
            respond("page", HttpStatusCode.OK, headersOf("Content-Type", "text/html; charset=UTF-8"))
        }
        val s = factory(engine).create(
            PluginDescriptor(
                metadata(),
                """
                local PAGE = "https://site.example/book/1"
                function getBookTitle(url) return "T" end
                function getBookCoverImageUrl(url) return http_get(PAGE).body end
                function getBookDescription(url) return http_get(PAGE).body end
                """.trimIndent(),
            ),
        )
        runBlocking { s.book("https://site.example/book/1") }
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun `book getters fetching different URLs hit the engine once each`() {
        val engine = MockEngine { _ ->
            respond("page", HttpStatusCode.OK, headersOf("Content-Type", "text/html; charset=UTF-8"))
        }
        val s = factory(engine).create(
            PluginDescriptor(
                metadata(),
                """
                function getBookTitle(url) return http_get("https://site.example/a").body end
                function getBookCoverImageUrl(url) return http_get("https://site.example/b").body end
                """.trimIndent(),
            ),
        )
        runBlocking { s.book("https://site.example/book/1") }
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun `http_post is not memoized within book`() {
        val engine = MockEngine { _ ->
            respond("page", HttpStatusCode.OK, headersOf("Content-Type", "text/html; charset=UTF-8"))
        }
        val s = factory(engine).create(
            PluginDescriptor(
                metadata(),
                """
                function getBookTitle(url) return http_post("https://site.example/api", "q=1").body end
                function getBookDescription(url) return http_post("https://site.example/api", "q=1").body end
                """.trimIndent(),
            ),
        )
        runBlocking { s.book("https://site.example/book/1") }
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun `memo is cleared after book so the next call re-fetches`() {
        val engine = MockEngine { _ ->
            respond("page", HttpStatusCode.OK, headersOf("Content-Type", "text/html; charset=UTF-8"))
        }
        val s = factory(engine).create(
            PluginDescriptor(
                metadata(),
                """
                function getBookTitle(url) return http_get("https://site.example/a").body end
                """.trimIndent(),
            ),
        )
        runBlocking { s.book("https://site.example/book/1") }
        runBlocking { s.book("https://site.example/book/1") }
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun `http_get outside book is not memoized`() {
        val engine = MockEngine { _ ->
            respond("page", HttpStatusCode.OK, headersOf("Content-Type", "text/html; charset=UTF-8"))
        }
        val s = factory(engine).create(
            PluginDescriptor(
                metadata(),
                """
                function getCatalogList(index)
                  http_get("https://site.example/list")
                  return { items = { { title = "x", url = "y" } }, hasNext = false }
                end
                function getBookTitle(url) return http_get("https://site.example/list").body end
                """.trimIndent(),
            ),
        )
        runBlocking { s.catalog(0) }
        runBlocking { s.book("https://site.example/book/1") }
        // catalog's GET runs with the memo off; book()'s reuses its own fresh memo.
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun `mutating one getter memo result does not poison the others`() {
        val engine = MockEngine { _ ->
            respond("page", HttpStatusCode.OK, headersOf("Content-Type", "text/html; charset=UTF-8"))
        }
        val s = factory(engine).create(
            PluginDescriptor(
                metadata(),
                """
                local PAGE = "https://site.example/book/1"
                function getBookTitle(url)
                  local r = http_get(PAGE)
                  r.body = "MUTATED"
                  return "T"
                end
                function getBookCoverImageUrl(url) return http_get(PAGE).body end
                function getBookDescription(url) return http_get(PAGE).body end
                """.trimIndent(),
            ),
        )
        val book = runBlocking { s.book("https://site.example/book/1") }.ok()
        assertEquals("page", book.coverUrl)
        assertEquals("page", book.description)
    }

    // ── bindings-before-execute seam ─────────────────────────────────────────

    @Test
    fun `http_get works inside plugin functions via mock engine`() {
        val s = source(
            """
            function getChapterText(html, url)
              local r = http_get("https://site.example/chapter/1")
              return r.body
            end
            """.trimIndent(),
        )
        val content = runBlocking { s.chapterContent("<div/>", "u") }.ok()
        assertEquals(ChapterContent.Text("remote:<p>from-http</p>"), content)
    }

    @Test
    fun `top-level require_lib works because bindings are installed before execute`() {
        val libSource = "return { wrap = function() return 'from-lib' end }"
        fileSystem.write("lua_libs/util.lua", libSource.encodeToByteArray())
        val libSha = sha256Hex(libSource.encodeToByteArray())
        val s = source(
            """
            local util = require_lib("util", "$libSha")
            function getChapterText(html, url) return util.wrap() end
            """.trimIndent(),
        )
        val content = runBlocking { s.chapterContent("<div/>", "u") }.ok()
        assertEquals(ChapterContent.Text("from-lib"), content)
    }

    // ── registry wiring (Task 14 Important 2) ────────────────────────────────

    @Test
    fun `registry install registers cf and ua, dispose clears them`() {
        val cf = CfOptions(statuses = listOf(418), markers = listOf("Teapot"))
        val registry = PluginRegistry(factory(), hostOptions)
        val handle = registry.install(
            PluginDescriptor(metadata(cf = cf, ua = "CustomUA/1.0"), "x = 1"),
        )
        assertNotNull(handle.source)
        assertEquals(cf, hostOptions.cfOptions("site.example"))
        assertEquals("CustomUA/1.0", hostOptions.userAgent("site.example"))

        handle.dispose()
        assertNull(hostOptions.cfOptions("site.example"))
        assertNull(hostOptions.userAgent("site.example"))
    }

    // ── filters / settingsSchema ─────────────────────────────────────────────

    @Test
    fun `filters converts filter list`() {
        val s = source(
            """
            function getFilterList()
              return { { id = "genre", title = "Genre", type = "select" } }
            end
            """.trimIndent(),
        )
        val filters = runBlocking { s.filters() }.ok()
        assertEquals("genre", filters.single()["id"])
        assertEquals("Genre", filters.single()["title"])
    }

    @Test
    fun `settings schema converts list`() {
        val s = source(
            """
            function getSettingsSchema()
              return { { key = "token", title = "Token", type = "text" } }
            end
            """.trimIndent(),
        )
        val schema = runBlocking { s.settingsSchema() }.ok()
        assertEquals("token", schema.single()["key"])
    }

    // ── catalogFiltered: NoveLA value shapes (LuaFilterSupport.toLuaTable) ────

    @Test
    fun `select filter reaches the plugin as a string, checkbox stays an array`() {
        val s = source(
            """
            function getFilterList()
              return {
                { type = "select",   key = "sortby", label = "Sort" },
                { type = "checkbox", key = "genre",  label = "Genre" },
              }
            end
            function getCatalogFiltered(index, filters)
              -- `.. filters["sortby"] ..` is the corpus idiom and the exact
              -- failure mode when a select arrives as a table.
              local sort = filters["sortby"] or "topweek"
              local g = ""
              for i, v in ipairs(filters["genre_included"] or {}) do g = g .. v .. "," end
              return { items = { { title = type(sort) .. "|" .. sort .. "|" .. g,
                                   url = "https://site.example/b" } }, hasNext = false }
            end
            """.trimIndent(),
        )
        val page = runBlocking {
            s.catalogFiltered(
                0,
                mapOf("sortby" to listOf("topweek"), "genre_included" to listOf("Action")),
            )
        }.ok()
        assertEquals("string|topweek|Action,", page.items.single().title)
    }

    @Test
    fun `empty select value is omitted so the plugin default applies`() {
        val s = source(
            """
            function getFilterList()
              return { { type = "select", key = "sortby", label = "Sort" } }
            end
            function getCatalogFiltered(index, filters)
              return { items = { { title = filters["sortby"] or "topweek",
                                   url = "https://site.example/b" } }, hasNext = false }
            end
            """.trimIndent(),
        )
        val page = runBlocking { s.catalogFiltered(0, mapOf("sortby" to emptyList())) }.ok()
        assertEquals("topweek", page.items.single().title)
    }

    @Test
    fun `unknown filter keys keep the array shape`() {
        val s = source(
            """
            function getCatalogFiltered(index, filters)
              return { items = { { title = type(filters["mystery"]),
                                   url = "https://site.example/b" } }, hasNext = false }
            end
            """.trimIndent(),
        )
        val page = runBlocking { s.catalogFiltered(0, mapOf("mystery" to listOf("x"))) }.ok()
        assertEquals("table", page.items.single().title)
    }

    // ── Task 15 review fix: nil handling on catalog paths ────────────────────

    @Test
    fun `search returning nil yields an empty page`() {
        val s = source("function getCatalogSearch(index, query) return nil end")
        val page = runBlocking { s.search(0, "nothing") }.ok()
        assertTrue(page.items.isEmpty())
        assertTrue(!page.hasNext)
    }

    @Test
    fun `catalog returning nil yields an empty page`() {
        val s = source("function getCatalogList(index) return nil end")
        val page = runBlocking { s.catalog(0) }.ok()
        assertTrue(page.items.isEmpty())
        assertTrue(!page.hasNext)
    }

    @Test
    fun `chapters returning nil is still a script error`() {
        val s = source("function getChapterList(url) return nil end")
        val result = runBlocking { s.chapters("u") }
        assertTrue(result.err() is dev.mirutime.core.api.PluginError.ScriptError)
    }

    // ── Task 15 review fix: host options read side ───────────────────────────

    @Test
    fun `per-host cf options from registry beat plugin metadata and survive sibling dispose`() {
        val aOptions = CfOptions(statuses = listOf(200), markers = listOf("Teapot"))
        val bOptions = CfOptions(statuses = listOf(403), markers = listOf("Alien"))
        // 200 + "Teapot" is a challenge ONLY under A's options.
        val teapotEngine = MockEngine { _ ->
            respond("Teapot!", HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
        }
        val script =
            """
            function getChapterText(html, url)
              local r = http_get("https://site.example/x")
              return r.errorType or r.body
            end
            """.trimIndent()

        // A installs through the registry: its options become the per-host entry.
        val registry = PluginRegistry(factory(teapotEngine, hostOptions), hostOptions)
        val handleA = registry.install(PluginDescriptor(metadata(id = "plugin.a", cf = aOptions), "x = 1"))

        // B is built directly with its OWN (different) metadata options.
        val b = factory(teapotEngine, hostOptions)
            .create(PluginDescriptor(metadata(id = "plugin.b", cf = bOptions), script))

        // The per-host override (A) wins over B's metadata: challenge fires.
        val challenged = runBlocking { b.chapterContent("", "u") }.ok()
        assertEquals(ChapterContent.Text("CfChallengeRequired"), challenged)

        // Disposing A removes the override: B falls back to its own metadata
        // (200 is not in Alien's statuses, body has no Alien marker).
        handleA.dispose()
        val plain = runBlocking { b.chapterContent("", "u") }.ok()
        assertEquals(ChapterContent.Text("Teapot!"), plain)
    }

    @Test
    fun `per-host user agent from registry beats metadata preset`() {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val recordingEngine = MockEngine { request ->
            requests += request
            respond("ok", HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
        }
        val script =
            """
            function getChapterText(html, url)
              local r = http_get("https://site.example/x")
              return r.body
            end
            """.trimIndent()

        val registry = PluginRegistry(factory(recordingEngine, hostOptions), hostOptions)
        val handleA = registry.install(
            PluginDescriptor(metadata(id = "plugin.a", ua = "RegistryUA/9.9"), "x = 1"),
        )
        val b = factory(recordingEngine, hostOptions).create(
            PluginDescriptor(metadata(id = "plugin.b", ua = "PresetUA/1.0"), script),
        )

        runBlocking { b.chapterContent("", "u") }
        assertEquals("RegistryUA/9.9", requests.last().headers[io.ktor.http.HttpHeaders.UserAgent])

        handleA.dispose()
        runBlocking { b.chapterContent("", "u") }
        assertEquals("PresetUA/1.0", requests.last().headers[io.ktor.http.HttpHeaders.UserAgent])
    }

    // ── Task 15 re-review fix: host normalization consistency ────────────────

    @Test
    fun `per-host options and ua are found for www urls`() {
        val aOptions = CfOptions(statuses = listOf(200), markers = listOf("Teapot"))
        val bOptions = CfOptions(statuses = listOf(403), markers = listOf("Alien"))
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond("Teapot!", HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
        }
        val script =
            """
            function getChapterText(html, url)
              local r = http_get("https://www.site.example/x")
              return r.errorType or r.body
            end
            """.trimIndent()

        val registry = PluginRegistry(factory(engine, hostOptions), hostOptions)
        registry.install(PluginDescriptor(metadata(id = "plugin.a", cf = aOptions, ua = "RegistryUA/9.9"), "x = 1"))
        val b = factory(engine, hostOptions)
            .create(PluginDescriptor(metadata(id = "plugin.b", cf = bOptions), script))

        // The www URL must resolve to the same registry key as "site.example":
        // per-host override (A) beats B's metadata for both CF and UA.
        val challenged = runBlocking { b.chapterContent("", "u") }.ok()
        assertEquals(ChapterContent.Text("CfChallengeRequired"), challenged)
        assertEquals(
            "RegistryUA/9.9",
            requests.last().headers[io.ktor.http.HttpHeaders.UserAgent],
        )
    }

    @Test
    fun `gate cooldown is shared between www and bare urls`() {
        val gate = dev.mirutime.core.network.cf.CfGate()
        val solver = dev.mirutime.core.host.FakeCfChallengeSolver()
        val engine = MockEngine { _ ->
            respond("Teapot!", HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
        }
        val client = dev.mirutime.core.network.PluginHttpClient(
            metadata = metadata(id = "plugin.a", cf = CfOptions(statuses = listOf(200), markers = listOf("Teapot"))),
            systemInfo = FakeSystemInfo(),
            cookieStore = FakeCookieStore(),
            logger = RecordingLogger(),
            engine = engine,
            gate = gate,
            solver = solver,
        )

        // First request (www): challenge -> solver runs once -> cooldown.
        kotlin.test.assertFailsWith<dev.mirutime.core.network.cf.CfChallengeRequiredException> {
            runBlocking { client.get("plugin.a", "https://www.site.example/a") }
        }
        assertEquals(1, solver.requests.size)
        assertEquals("site.example", solver.requests.single().host)

        // Second request (bare host, same challenge): the SAME cooldown must
        // fast-fail it without re-running the solver.
        kotlin.test.assertFailsWith<dev.mirutime.core.network.cf.CfChallengeRequiredException> {
            runBlocking { client.get("plugin.a", "https://site.example/b") }
        }
        assertEquals(1, solver.requests.size)
        assertTrue(runBlocking { gate.isOnCooldown("site.example") })
    }

    // ── factory pipeline binding coverage ────────────────────────────────────

    @Test
    fun `factory pipeline installs html json and crypto bindings`() {
        // Ethalon NoveLA installs html/hash/etc. into EVERY plugin session;
        // a real plugin must be able to call them right after install.
        val s = source(
            """
            function getCatalogList(index)
              local el = html_parse('<a href="/book/1">Hello</a>', 'https://site.example')
              local link = html_select_first(el, 'a')
              local decoded = json_parse('{"k":"v"}')
              return {
                items = {
                  { title = html_text(link) .. ':' .. decoded.k .. ':' .. #sha256('abc'),
                    url = html_attr(link, 'href') },
                },
                hasNext = false,
              }
            end
            """.trimIndent(),
        )
        val page = runBlocking { s.catalog(0) }.ok()
        assertEquals(listOf("/book/1"), page.items.map { it.url })
        assertEquals("Hello:v:32", page.items.single().title)
    }

    // ── default SSRF guard ───────────────────────────────────────────────────

    @Test
    fun `default factory pipeline blocks loopback http_get`() {
        // Fresh engine: proves the request never leaves the client.
        val engine = MockEngine { _ -> respond("should not happen") }
        // No ssrfGuard override — this is the SHIPPED default wiring.
        val s = LuaPluginSourceFactory(
            systemInfo = FakeSystemInfo(),
            cookieStore = FakeCookieStore(),
            keyValueStore = FakeKeyValueStore(),
            logger = RecordingLogger(),
            engine = engine,
            fileSystem = fileSystem,
            hostOptions = null,
        ).create(
            PluginDescriptor(
                metadata(),
                """
                function getCatalogList(index)
                  local r = http_get("http://127.0.0.1/admin")
                  return { items = { { title = r.errorType or "no-error", url = "x" } }, hasNext = false }
                end
                """.trimIndent(),
            ),
        )
        val page = runBlocking { s.catalog(0) }.ok()
        assertEquals("SsrfBlocked", page.items.single().title)
        assertTrue(engine.requestHistory.isEmpty(), "blocked request must never reach the engine")
    }

    // ── NoveLA-parity load-time globals ───────────────────────────────────────
    // The engine reads the script's own globals after the chunk runs and
    // registers them per baseUrl host (LuaSourceAdapter.init). Unique hosts
    // per test: the shared hostOptions registry is append-only here.

    @Test
    fun `source exposes the plugin referer global`() {
        val s = source(
            """
            referer = "https://covers.example/"
            function getChapterText(html, url) return "t" end
            """.trimIndent(),
        )
        assertEquals("https://covers.example/", s.referer)
        val bare = source("""function getChapterText(html, url) return "t" end""")
        assertEquals("", bare.referer)
    }

    @Test
    fun `referer is captured at load, not read live`() {
        val session = createLuaSession(
            "refload",
            """
            referer = "https://load.example/"
            function getChapterText(html, url) return "t" end
            """.trimIndent(),
        )
        val s = LuaPluginSource(session, metadata(id = "refload"))
        // Mutated after construction but before any read: a live/by-lazy read
        // would observe the new value.
        session.globals.set("referer", LuaValue.valueOf("https://changed.example/"))
        assertEquals("https://load.example/", s.referer)
    }

    @Test
    fun `load registers the plugin referer per host`() {
        val options = HostOptionsRegistry()
        val meta = metadata(id = "loadreg.referer").copy(baseUrl = "https://regref.example")
        factory(mockEngine, options).create(
            PluginDescriptor(meta, """referer = "https://regref.example/" """),
        )
        assertEquals("https://regref.example/", options.referer("regref.example"))
    }

    @Test
    fun `engine-registered referer is cleared on dispose`() {
        val options = HostOptionsRegistry()
        val registry = PluginRegistry(factory(mockEngine, options), options)
        val meta = metadata(id = "loadreg.dispose").copy(baseUrl = "https://regdisp.example")
        val handle = registry.install(
            PluginDescriptor(meta, """referer = "https://regdisp.example/" """),
        )
        assertNotNull(handle.source)
        assertEquals("https://regdisp.example/", options.referer("regdisp.example"))
        handle.dispose()
        assertNull(options.referer("regdisp.example"))
    }

    @Test
    fun `load registers cf_options trigger markers and detection changes`() {
        val options = HostOptionsRegistry()
        val meta = metadata(id = "loadreg.cf").copy(baseUrl = "https://regcf.example")
        // NOTE: metadata carries NO cfOptions here — the script declaration
        // alone must complete the chain to the detector.
        factory(mockEngine, options).create(
            PluginDescriptor(
                meta,
                """cf_options = { trigger_markers = { "x-waf-block" } }""",
            ),
        )
        val registered = options.cfOptions("regcf.example")
        assertNotNull(registered)
        assertEquals(listOf("x-waf-block"), registered.triggerMarkers)
        assertTrue(
            CloudflareDetector().isChallenge(200, "blocked by x-waf-block", registered, serverHeader = "nginx"),
        )
    }

    @Test
    fun `load calls getUserAgentPreset and registers per source and host`() {
        PluginUserAgentRegistry.clear()
        try {
            val options = HostOptionsRegistry()
            val meta = metadata(id = "loadreg.ua").copy(baseUrl = "https://regua.example")
            factory(mockEngine, options).create(
                PluginDescriptor(
                    meta,
                    """function getUserAgentPreset() return "Chrome Mobile" end""",
                ),
            )
            assertEquals("Chrome Mobile", PluginUserAgentRegistry.getPreset("loadreg.ua"))
            assertEquals("Chrome Mobile", PluginUserAgentRegistry.getPresetForHost("regua.example"))
            assertEquals(UserAgentPresets.CHROME_150_ANDROID, options.userAgent("regua.example"))
        } finally {
            PluginUserAgentRegistry.clear()
        }
    }

    @Test
    fun `blank or erroring getUserAgentPreset registers nothing`() {
        PluginUserAgentRegistry.clear()
        try {
            val options = HostOptionsRegistry()
            factory(mockEngine, options).create(
                PluginDescriptor(
                    metadata(id = "loadreg.uablank").copy(baseUrl = "https://reguablank.example"),
                    """function getUserAgentPreset() return "  " end""",
                ),
            )
            assertNull(PluginUserAgentRegistry.getPreset("loadreg.uablank"))
            assertNull(options.userAgent("reguablank.example"))
            // A throw inside the preset must not fail the install.
            val failed = factory(mockEngine, options).create(
                PluginDescriptor(
                    metadata(id = "loadreg.uaerr").copy(baseUrl = "https://reguaerr.example"),
                    """function getUserAgentPreset() error("boom") end""",
                ),
            )
            assertNotNull(failed)
            assertNull(PluginUserAgentRegistry.getPreset("loadreg.uaerr"))
        } finally {
            PluginUserAgentRegistry.clear()
        }
    }

    @Test
    fun `plugin charset global decodes gbk responses`() {
        val gbk = "你好世界".toByteArray(Charset.forName("GBK"))
        val engine = MockEngine {
            respond(content = gbk, status = HttpStatusCode.OK, headers = headersOf("Content-Type", "text/html"))
        }
        val s = factory(engine).create(
            PluginDescriptor(
                metadata().copy(charset = "GBK"),
                """
                function getChapterText(html, url)
                  return http_get("https://site.example/gbk").body
                end
                """.trimIndent(),
            ),
        )
        assertEquals(ChapterContent.Text("你好世界"), runBlocking { s.chapterContent("<div/>", "u") }.ok())
    }

    // ── chapter list / content parity ─────────────────────────────────────────

    @Test
    fun `getPageList returning non-table yields empty pages, not an error`() {
        val s = source("""function getPageList(html, url) return "nope" end""")
        assertEquals(ChapterContent.Pages(emptyList()), runBlocking { s.chapterContent("<div/>", "u") }.ok())
        val n = source("""function getPageList(html, url) return nil end""")
        assertEquals(ChapterContent.Pages(emptyList()), runBlocking { n.chapterContent("<div/>", "u") }.ok())
    }

    @Test
    fun `chapter uploaded converts numbers to epoch millis, drops strings`() {
        val s = source(
            """
            function getChapterList(url)
              return {
                { title = "A", url = "a", uploaded = 1712345678 },
                { title = "B", url = "b", uploaded = "2024-01-01" },
                { title = "C", url = "c" },
              }
            end
            """.trimIndent(),
        )
        val chapters = runBlocking { s.chapters("u") }.ok()
        assertEquals(1712345678L, chapters[0].uploaded)
        assertNull(chapters[1].uploaded)
        assertNull(chapters[2].uploaded)
    }

    @Test
    fun `video mime values are whitelist-normalized`() {
        val s = source(
            """
            function getVideoList(url)
              return {
                { url = "https://x/1.m3u8", mime = "hls" },
                { url = "https://x/2.mp4", mime = "video/mp4" },
                { url = "https://x/3", mime = "application/octet-stream" },
                { url = "https://x/4", mime = "video/webm" },
                { url = "https://x/5" },
              }
            end
            """.trimIndent(),
        )
        val video = assertIs<ChapterContent.Video>(runBlocking { s.chapterContent("", "https://x/ep") }.ok())
        assertEquals("application/x-mpegURL", video.streams[0].mime)
        assertEquals("video/mp4", video.streams[1].mime)
        assertNull(video.streams[2].mime)
        assertEquals("video/webm", video.streams[3].mime)
        assertNull(video.streams[4].mime)
    }

    @Test
    fun `transformChapterUrl defaults to identity`() {
        val s = source("""function getChapterText(html, url) return "t" end""")
        assertEquals("https://site.example/c/1", runBlocking { s.transformChapterUrl("https://site.example/c/1") })
    }

    // ── catalog force-network (NoveLA withForceNetwork) ───────────────────────
    // getCatalogList/getCatalogFiltered skip the response cache; search does not.

    @Test
    fun `catalog forces network while search serves cache`() {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("page", HttpStatusCode.OK, headersOf("Content-Type", "text/html; charset=UTF-8"))
        }
        val script = """
            function getCatalogList(index)
              http_get("https://site.example/data")
              return { items = { { title = "T", url = "u" } }, hasNext = false }
            end
            function getCatalogSearch(index, query)
              http_get("https://site.example/data")
              return { items = { { title = "T", url = "u" } }, hasNext = false }
            end
        """.trimIndent()
        // Frozen cache clock: the client's monotonic stamp would otherwise
        // outrun the cache's wall clock and expire every entry at once (the
        // host-must-share-clocks contract documented on PluginHttpClient).
        val s = factory(engine, cache = ResponseCache(clock = { 0L })).create(PluginDescriptor(metadata(), script))

        runBlocking { s.catalog(0) }.ok()
        runBlocking { s.catalog(0) }.ok()
        assertEquals(2, calls, "catalog must skip the cache (NoveLA withForceNetwork)")
        runBlocking { s.search(0, "q") }.ok()
        runBlocking { s.search(0, "q") }.ok()
        assertEquals(2, calls, "search must serve the cached copy")
    }

    // ── flattened filters keep TYPE info for the host (item 18 audit) ────────

    @Test
    fun `flattened filters preserve each filter type for the host`() {
        val s = source(
            """
            function getFilterList()
              return {
                { type = "select", key = "sort", title = "Sort",
                  options = { { value = "a", label = "A" } } },
                { type = "checkbox", key = "genre", title = "Genre" },
              }
            end
            """.trimIndent(),
        )
        val filters = runBlocking { s.filters() }.ok()
        assertEquals(listOf("select", "checkbox"), filters.map { it["type"] })
        assertEquals("1", filters[0]["options.count"])
        assertEquals("a", filters[0]["options.0.value"])
    }
}
