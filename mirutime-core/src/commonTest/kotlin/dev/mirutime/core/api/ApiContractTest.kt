package dev.mirutime.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiContractTest {

    // --- ContentType ---
    @Test
    fun contentTypeValues() {
        assertEquals(
            setOf(ContentType.NOVEL, ContentType.MANGA, ContentType.VIDEO),
            ContentType.entries.toSet()
        )
    }

    // --- PluginError sealed exhaustiveness ---
    @Test
    fun pluginErrorExhaustiveWhen() {
        val errors: List<PluginError> = listOf(
            PluginError.UserMessage("t", "m", "https://auth"),
            PluginError.UserMessage("t", "m"),
            PluginError.Network("boom"),
            PluginError.DnsFailure("example.com"),
            PluginError.CfChallengeRequired("example.com"),
            PluginError.ResponseTooLarge(1_048_576L),
            PluginError.UnsupportedApi(required = 2, actual = 1),
            PluginError.ScriptError("detail"),
        )
        val labels = errors.map { e ->
            when (e) {
                is PluginError.UserMessage -> "user:${e.title}:${e.message}:${e.authUrl}"
                is PluginError.Network -> "network:${e.cause}"
                is PluginError.DnsFailure -> "dns:${e.host}"
                is PluginError.CfChallengeRequired -> "cf:${e.host}"
                is PluginError.ResponseTooLarge -> "too-large:${e.limit}"
                is PluginError.UnsupportedApi -> "api:${e.required}:${e.actual}"
                is PluginError.ScriptError -> "script:${e.detail}"
            }
        }
        assertEquals(
            listOf(
                "user:t:m:https://auth",
                "user:t:m:null",
                "network:boom",
                "dns:example.com",
                "cf:example.com",
                "too-large:1048576",
                "api:2:1",
                "script:detail",
            ),
            labels
        )
    }

    // --- ChapterContent sealed exhaustiveness ---
    @Test
    fun chapterContentExhaustiveWhen() {
        val contents: List<ChapterContent> = listOf(
            ChapterContent.Text("hello"),
            ChapterContent.Pages(listOf("https://a/1.png", "https://a/2.png")),
            ChapterContent.Video(
                listOf(
                    VideoStream(
                        url = "https://a/ep1.m3u8",
                        headers = mapOf("Referer" to "https://a"),
                        mime = "application/x-mpegURL",
                        quality = "1080p",
                        subtitles = listOf(SubtitleTrack("https://a/sub.vtt", "en", "English")),
                    )
                )
            ),
        )
        val labels = contents.map { c ->
            when (c) {
                is ChapterContent.Text -> "text:${c.text}"
                is ChapterContent.Pages -> "pages:${c.pageUrls.size}"
                is ChapterContent.Video -> "video:${c.streams.first().quality}"
            }
        }
        assertEquals(listOf("text:hello", "pages:2", "video:1080p"), labels)
    }

    // --- Outcome Ok/Err + helpers ---
    @Test
    fun outcomeOkRoundTrip() {
        val ok: Outcome<Int> = Outcome.Ok(42)
        assertEquals(42, ok.getOrNull())
        assertEquals(42, ok.getOrElse { error("must not be called") })
    }

    @Test
    fun outcomeErrRoundTrip() {
        val err: Outcome<Int> = Outcome.Err(PluginError.Network("down"))
        assertNull(err.getOrNull())
        assertEquals(-1, err.getOrElse { -1 })
        val e = err.getOrElse { (it as PluginError.Network).cause }
        assertEquals("down", e)
    }

    // --- PluginSource shape (compile-time contract; metadata + suspend signatures) ---
    @Test
    fun pluginSourceMetadataType() {
        val meta = PluginMetadata(
            id = "dev.mirutime.demo",
            name = "Demo",
            baseUrl = "https://demo.example",
            contentType = ContentType.NOVEL,
            language = "en",
            api = 1,
            version = "0.1.0",
            sha256 = "abc",
        )
        val source = object : PluginSource {
            override val metadata: PluginMetadata = meta
            override suspend fun catalog(index: Int): Outcome<CatalogPage> =
                Outcome.Ok(CatalogPage(listOf(CatalogItem("T", "u")), hasNext = false))
            override suspend fun search(index: Int, query: String): Outcome<CatalogPage> =
                Outcome.Err(PluginError.Network("offline"))
            override suspend fun catalogFiltered(
                index: Int,
                filters: Map<String, List<String>>,
            ): Outcome<CatalogPage> = Outcome.Ok(CatalogPage(emptyList(), hasNext = false))
            override suspend fun book(url: String): Outcome<BookMeta> = Outcome.Ok(BookMeta("T"))
            override suspend fun chapters(url: String): Outcome<List<Chapter>> =
                Outcome.Ok(listOf(Chapter("Ch 1", "u1", volume = "1", uploaded = 1767225600000L)))
            override suspend fun chapterContent(html: String, url: String): Outcome<ChapterContent> =
                Outcome.Ok(ChapterContent.Text("body"))
            override suspend fun filters(): Outcome<List<Map<String, String>>> =
                Outcome.Ok(listOf(mapOf("id" to "genre", "title" to "Genre", "type" to "select")))
            override suspend fun settingsSchema(): Outcome<List<Map<String, String>>> =
                Outcome.Ok(listOf(mapOf("key" to "token", "title" to "Token", "type" to "text")))
        }
        assertEquals(meta, source.metadata)
    }

    @Test
    fun dataClassDefaults() {
        val item = CatalogItem("T", "u")
        assertNull(item.coverUrl)
        assertNull(item.rating)
        val stream = VideoStream("s")
        assertTrue(stream.headers.isEmpty())
        assertNull(stream.mime)
        assertNull(stream.quality)
        assertTrue(stream.subtitles.isEmpty())
        val book = BookMeta("T")
        assertTrue(book.genres.isEmpty())
    }
}
