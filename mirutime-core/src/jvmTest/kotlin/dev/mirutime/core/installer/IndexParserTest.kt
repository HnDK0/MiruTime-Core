package dev.mirutime.core.installer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IndexParserTest {

    private val rootIndex = """
        languages:
          en:
            name: "English"
            url: "https://example.com/en/index.yaml"
        sources:
          - id: "asurascans"
            name: "Asura Scans"
            version: "1.7.1"
            url: "https://example.com/en/asurascans.lua"
            sha256: "abc123"
          - id: "broken"
            name: "No URL"
        libraries:
          - id: "hls"
            url: "https://example.com/libs/hls.lua"
            sha256: "def456"
          - id: "nameless"
    """.trimIndent()

    @Test
    fun `parses sources and libraries sections`() {
        val index = parsePluginIndex(rootIndex)

        assertEquals(1, index.sources.size)
        val source = index.sources.single()
        assertEquals("asurascans", source.id)
        assertEquals("Asura Scans", source.name)
        assertEquals("1.7.1", source.version)
        assertEquals("https://example.com/en/asurascans.lua", source.url)
        assertEquals("abc123", source.sha256)

        // The url-less source and the id-only library are skipped.
        assertEquals(1, index.libraries.size)
        assertEquals("hls", index.libraries.single().id)
        assertNull(index.libraries.single().name)
    }

    @Test
    fun `missing sections parse as empty lists`() {
        val index = parsePluginIndex("language: \"en\"\nname: \"English\"")
        assertEquals(emptyList(), index.sources)
        assertEquals(emptyList(), index.libraries)
    }

    @Test
    fun `parses api field as int or string and defaults to null when absent`() {
        val index = parsePluginIndex(
            """
            sources:
              - id: "intapi"
                url: "https://example.com/a.lua"
                sha256: "aaa"
                api: 2
              - id: "stringapi"
                url: "https://example.com/b.lua"
                sha256: "bbb"
                api: "3"
              - id: "noapi"
                url: "https://example.com/c.lua"
                sha256: "ccc"
            """.trimIndent(),
        )
        assertEquals(listOf(2, 3, null), index.sources.map { it.api })
    }

    @Test
    fun `non mapping root throws`() {
        assertFailsWith<IndexParseException> { parsePluginIndex("- a\n- b") }
    }

    @Test
    fun `section that is not a list throws`() {
        assertFailsWith<IndexParseException> { parsePluginIndex("sources: just-a-string") }
    }
}
