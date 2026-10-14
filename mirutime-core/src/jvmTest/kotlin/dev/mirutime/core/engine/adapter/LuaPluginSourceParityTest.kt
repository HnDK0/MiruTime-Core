package dev.mirutime.core.engine.adapter

import dev.mirutime.core.api.ChapterContent
import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginDescriptor
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.api.PluginSource
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeFileSystem
import dev.mirutime.core.host.FakeKeyValueStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// Parity coverage for the NoveLA LuaSourceAdapter semantics fixed in
// LuaPluginSource: per-field book getters, the parsePage chapter path, the
// flattened filter options and the `lang` subtitle key.
class LuaPluginSourceParityTest {

    private val mockEngine = MockEngine { _ ->
        respond("ok", HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
    }

    private fun metadata() = PluginMetadata(
        id = "test.parity",
        name = "Test",
        baseUrl = "https://site.example",
        contentType = ContentType.NOVEL,
        language = "en",
        api = 1,
        version = "0.1.0",
        sha256 = "abc",
    )

    private fun source(script: String): PluginSource = LuaPluginSourceFactory(
        systemInfo = FakeSystemInfo(),
        cookieStore = FakeCookieStore(),
        keyValueStore = FakeKeyValueStore(),
        logger = RecordingLogger(),
        engine = mockEngine,
        fileSystem = FakeFileSystem(),
        hostOptions = null,
        ssrfGuard = null,
    ).create(PluginDescriptor(metadata(), script))

    private fun <T> Outcome<T>.ok(): T = (this as Outcome.Ok).value

    @Test
    fun `book aggregates per-field getters like NoveLA`() {
        val s = source(
            """
            function getBookTitle(url) return "The Book" end
            function getBookCoverImageUrl(url) return "https://img.example/c.jpg" end
            function getBookDescription(url) return "desc" end
            function getBookGenres(url) return { "Fantasy", "Action" } end
            function getBookRating(url) return 4.2 end
            function getBookStatus(url) return "ongoing" end
            function getBookLastUpdate(url) return 1712345678 end
            """.trimIndent(),
        )
        val book = runBlocking { s.book("https://site.example/book/1") }.ok()
        assertEquals("The Book", book.title)
        assertEquals("https://img.example/c.jpg", book.coverUrl)
        assertEquals("desc", book.description)
        assertEquals(listOf("Fantasy", "Action"), book.genres)
        assertEquals("4.2", book.rating)
        assertEquals("ongoing", book.status)
        assertEquals("1712345678", book.lastUpdated)
    }

    @Test
    fun `missing optional book getters yield defaults`() {
        val s = source("function getBookTitle(url) return \"Only Title\" end")
        val book = runBlocking { s.book("u") }.ok()
        assertEquals("Only Title", book.title)
        assertEquals(null, book.coverUrl)
        assertEquals(null, book.rating)
        assertEquals(emptyList(), book.genres)
    }

    @Test
    fun `getBookMeta is used only when per-field getters are absent`() {
        val s = source(
            """
            function getBookMeta(url)
              return { title = "Meta Title", description = "meta desc" }
            end
            """.trimIndent(),
        )
        val book = runBlocking { s.book("u") }.ok()
        assertEquals("Meta Title", book.title)
        assertEquals("meta desc", book.description)
    }

    @Test
    fun `chapters fall back to parsePage when getChapterList is absent`() {
        val s = source(
            """
            function parsePage(bookUrl, page)
              if page == 1 then
                return { chapters = { { title = "Ch 1", url = "u1" } }, totalPages = 2 }
              end
              return { chapters = { { title = "Ch 2", url = "u2" } }, totalPages = 2 }
            end
            """.trimIndent(),
        )
        val chapters = runBlocking { s.chapters("https://site.example/book/1") }.ok()
        assertEquals(listOf("Ch 1", "Ch 2"), chapters.map { it.title })
        assertEquals(listOf("u1", "u2"), chapters.map { it.url })
    }

    @Test
    fun `parsePage takes priority over getChapterList when both exist`() {
        val s = source(
            """
            function getChapterList(url) return { { title = "from-list", url = "l1" } } end
            function parsePage(bookUrl, page) return { chapters = { { title = "from-page", url = "p1" } }, totalPages = 1 } end
            """.trimIndent(),
        )
        val chapters = runBlocking { s.chapters("u") }.ok()
        assertEquals(listOf("from-page"), chapters.map { it.title })
    }

    @Test
    fun `chapterListHash reads the lua function and defaults to null`() {
        val withHash = source(
            """
            function getChapterListHash(url) return "hash-123" end
            """.trimIndent(),
        ) as LuaPluginSource
        assertEquals("hash-123", runBlocking { withHash.chapterListHash("u") }.ok())

        val without = source("x = 1") as LuaPluginSource
        assertEquals(null, runBlocking { without.chapterListHash("u") }.ok())
    }

    @Test
    fun `filters preserve nested option lists via dotted keys`() {
        val s = source(
            """
            function getFilterList()
              return {
                {
                  type = "sort",
                  key = "order",
                  label = "Sort by",
                  defaultValue = "weekly",
                  defaultAscending = false,
                  options = {
                    { value = "weekly", label = "Weekly" },
                    { value = "new", label = "Newest" },
                  },
                },
              }
            end
            """.trimIndent(),
        )
        val filter = runBlocking { s.filters() }.ok().single()
        assertEquals("sort", filter["type"])
        assertEquals("order", filter["key"])
        assertEquals("false", filter["defaultAscending"])
        assertEquals("2", filter["options.count"])
        assertEquals("weekly", filter["options.0.value"])
        assertEquals("Weekly", filter["options.0.label"])
        assertEquals("new", filter["options.1.value"])
        assertEquals("Newest", filter["options.1.label"])
    }

    @Test
    fun `subtitle track reads lang key`() {
        val s = source(
            """
            function getVideoList(url)
              return { {
                url = "https://v.example/1.m3u8",
                subtitles = { { url = "https://v.example/es.srt", label = "Espanol", lang = "es" } },
              } }
            end
            """.trimIndent(),
        )
        val video = assertIs<ChapterContent.Video>(runBlocking { s.chapterContent("", "u") }.ok())
        assertEquals("es", video.streams.single().subtitles.single().language)
        assertEquals("Espanol", video.streams.single().subtitles.single().label)
    }
}
