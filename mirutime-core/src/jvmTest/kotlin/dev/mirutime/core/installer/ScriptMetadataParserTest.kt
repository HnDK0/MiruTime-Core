package dev.mirutime.core.installer

import dev.mirutime.core.api.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptMetadataParserTest {

    // Real external-sources header (id/komiku.lua shape).
    private val header = """
        id       = "komiku"
        name     = "Komiku"
        version  = "1.0.0"
        baseUrl  = "https://komiku.org/"
        language = "id"
        icon     = "https://x/komiku.png"
        content_type = "manga"
        description = "Manga source"
        cf_options = {
            whitelist       = false,
            ignore_markers  = { "turnstile", "Ray ID" },
            trigger_markers = { "but-captcha" },
        }
        function getCatalogList(index) return { items = {} } end
    """.trimIndent()

    @Test
    fun `reads the whole header including cf_options`() {
        val meta = ScriptMetadataParser.parse(header)

        assertEquals("komiku", meta.id)
        assertEquals("Komiku", meta.name)
        assertEquals("1.0.0", meta.version)
        assertEquals("https://komiku.org/", meta.baseUrl)
        assertEquals("id", meta.language)
        assertEquals("manga", meta.contentType)
        assertEquals("https://x/komiku.png", meta.icon)
        assertEquals("Manga source", meta.description)
        assertEquals(
            ScriptCfOptions(
                whitelist = false,
                ignoreMarkers = listOf("turnstile", "Ray ID"),
                triggerMarkers = listOf("but-captcha"),
            ),
            meta.cfOptions,
        )
    }

    @Test
    fun `defaults are null when the header omits fields`() {
        val meta = ScriptMetadataParser.parse("local x = 1\nfunction f() return 1 end")
        assertNull(meta.id)
        assertNull(meta.name)
        assertNull(meta.contentType)
        assertNull(meta.cfOptions)
    }

    @Test
    fun `content_type outside manga-novel-video is dropped`() {
        assertNull(ScriptMetadataParser.parse("""content_type = "galgame"""").contentType)
        assertEquals("novel", ScriptMetadataParser.parse("""content_type = "NOVEL"""").contentType)
    }

    @Test
    fun `a top-level require_lib and a later throw do not hide the header`() {
        // NoveLA plugins call require_lib(id) with one arg at top level; the
        // parser stubs it so the 1-arg call cannot abort the header.
        val meta = ScriptMetadataParser.parse(
            """
            id = "p"
            local urls = require_lib("urls")
            error("boom")
            """.trimIndent(),
        )
        assertEquals("p", meta.id)
    }

    @Test
    fun `toPluginMetadata fills fallbacks and maps content type`() {
        val meta = ScriptMetadataParser.parse("""content_type = "video"""")

        val plugin = meta.toPluginMetadata(fallbackId = "entry.id", api = 1, sha256 = "sha")

        assertEquals("entry.id", plugin.id)
        assertEquals("entry.id", plugin.name)
        assertEquals(ContentType.VIDEO, plugin.contentType)
        assertEquals("en", plugin.language)
        assertEquals("1.0.0", plugin.version)
        assertEquals(1, plugin.api)
        assertEquals("sha", plugin.sha256)
    }

    @Test
    fun `toPluginMetadata keeps declared values`() {
        val plugin = ScriptMetadataParser.parse(header).toPluginMetadata("fallback", 1, "sha")
        assertEquals("komiku", plugin.id)
        assertEquals("Komiku", plugin.name)
        assertEquals("id", plugin.language)
        assertEquals(ContentType.MANGA, plugin.contentType)
        assertTrue(plugin.baseUrl == "https://komiku.org/")
    }

    // ── charset / referer / language / icon passthrough ──────────────────────

    @Test
    fun `reads charset and referer globals`() {
        val meta = ScriptMetadataParser.parse(
            """
            charset = "GBK"
            referer = "https://site.example/"
            """.trimIndent(),
        )
        assertEquals("GBK", meta.charset)
        assertEquals("https://site.example/", meta.referer)
    }

    @Test
    fun `blank charset and referer read as null`() {
        val meta = ScriptMetadataParser.parse(
            """
            charset = "  "
            referer = "   "
            """.trimIndent(),
        )
        assertNull(meta.charset)
        assertNull(meta.referer)
    }

    @Test
    fun `non-string referer reads as null like NoveLA`() {
        assertNull(ScriptMetadataParser.parse("""referer = true""").referer)
        assertNull(ScriptMetadataParser.parse("""referer = {}""").referer)
        assertNull(ScriptMetadataParser.parse("-- no referer").referer)
    }

    // NoveLA LuaSourceAdapter.kt:101-105: mtl/multi fold to the MTL marker,
    // everything else keeps its (lowercased, trimmed) code.
    @Test
    fun `language normalizes mtl and multi`() {
        assertEquals("mtl", ScriptMetadataParser.parse("""language = "Mtl"""").language)
        assertEquals("mtl", ScriptMetadataParser.parse("""language = "MTL"""").language)
        assertEquals("mtl", ScriptMetadataParser.parse("""language = "multi"""").language)
        assertEquals("en", ScriptMetadataParser.parse("""language = "en"""").language)
        assertEquals("zh", ScriptMetadataParser.parse("""language = "zh" """).language)
        assertNull(ScriptMetadataParser.parse("-- none").language)
    }

    @Test
    fun `toCfOptions maps script marker sets`() {
        val options = ScriptCfOptions(
            whitelist = true,
            ignoreMarkers = listOf("a"),
            triggerMarkers = listOf("b"),
        ).toCfOptions()
        assertTrue(options.whitelist)
        assertEquals(listOf("a"), options.ignoreMarkers)
        assertEquals(listOf("b"), options.triggerMarkers)
    }

    @Test
    fun `toPluginMetadata passes icon description charset and cfOptions through`() {
        val plugin = ScriptMetadataParser.parse(header).toPluginMetadata("fallback", 1, "sha")
        assertEquals("https://x/komiku.png", plugin.icon)
        assertEquals("Manga source", plugin.description)
        assertEquals(
            listOf("but-captcha"),
            plugin.cfOptions?.triggerMarkers,
        )
        assertEquals(
            listOf("turnstile", "Ray ID"),
            plugin.cfOptions?.ignoreMarkers,
        )
    }

    @Test
    fun `toPluginMetadata keeps charset`() {
        val plugin = ScriptMetadataParser.parse("""charset = "GBK"""").toPluginMetadata("f", 1, "sha")
        assertEquals("GBK", plugin.charset)
        assertNull(ScriptMetadataParser.parse("-- none").toPluginMetadata("f", 1, "sha").charset)
    }
}
