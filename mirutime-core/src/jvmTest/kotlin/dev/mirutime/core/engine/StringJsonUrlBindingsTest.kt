package dev.mirutime.core.engine

import dev.mirutime.core.engine.binding.JsonBindings
import dev.mirutime.core.engine.binding.StringBindings
import dev.mirutime.core.engine.binding.UrlBindings
import org.junit.Test

class StringJsonUrlBindingsTest {

    private fun eval(code: String): LuaCallResult {
        val session = createLuaSession("eval", code)
        StringBindings.install(session)
        JsonBindings.install(session)
        UrlBindings.install(session)
        return session.call("main", emptyList())
    }

    // -- regex --

    @Test
    fun `regex_match returns 1-based array of full matches`() {
        val r = eval(
            """
            function main()
              local m = regex_match("chapter 42 text / chapter 7 end", "chapter\\s+\\d+")
              return m[1] .. "|" .. m[2] .. "|" .. tostring(m[3])
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == LuaData.LuaStr("chapter 42|chapter 7|nil")) { r.data.toString() }
    }

    @Test
    fun `regex_match returns empty table on no match and on bad pattern`() {
        val r = eval(
            """
            function main()
              local miss = regex_match("no numbers here", "\\d+")
              local bad = regex_match("text", "[unclosed")
              return #miss .. "|" .. tostring(miss[1]) .. "|" .. #bad
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == LuaData.LuaStr("0|nil|0")) { r.data.toString() }
    }

    @Test
    fun `regex_replace replaces all occurrences including cyrillic`() {
        val r = eval(
            """
            function main()
              return regex_replace("a1a2a3", "[0-9]", "X") .. "|"
                .. regex_replace("Глава 1: Глава 2", "Глава", "Chapter")
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == LuaData.LuaStr("aXaXaX|Chapter 1: Chapter 2")) { r.data.toString() }
    }

    // -- json --

    @Test
    fun `json_parse converts nested structure`() {
        val r = eval(
            """
            function main()
              local t = json_parse('{"a": [1, 2.5, "x", true, null], "b": {"c": "d"}}')
              return t.b.c
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == LuaData.LuaStr("d")) { r.data.toString() }

        val r2 = eval(
            """
            function main()
              local t = json_parse('{"a": [1, 2.5, "x", true, null], "b": {"c": "d"}}')
              return t.a[1] + t.a[2]
            end
            """.trimIndent()
        )
        check(r2.error == null) { r2.error!! }
        check((r2.data as? LuaData.LuaNum)?.value == 3.5) { r2.data.toString() }
    }

    @Test
    fun `json_parse returns nil on invalid json`() {
        // NoveLA LuaSourceLoader.kt:1313-1317: parse failure → NIL, so Lua
        // code can fall back (`or {}`) without a pcall.
        val r = eval(
            """
            function main()
              return json_parse("{broken") == nil and "is-nil" or "not-nil"
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == LuaData.LuaStr("is-nil")) { r.data.toString() }
        val r2 = eval(
            """
            function main()
              local t = json_parse("{broken") or { fallback = true }
              return t.fallback
            end
            """.trimIndent()
        )
        check(r2.error == null) { r2.error!! }
        check(r2.data == LuaData.LuaBool(true)) { r2.data.toString() }
    }

    // -- url --

    @Test
    fun `url_resolve resolves relative against base`() {
        val r = eval(
            """
            function main()
              return url_resolve("https://example.com/books/", "chapter/1")
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == LuaData.LuaStr("https://example.com/books/chapter/1")) { r.data.toString() }
    }

    @Test
    fun `url_resolve keeps absolute and passes through on error`() {
        val r = eval(
            """
            function main()
              local abs = url_resolve("https://example.com/", "https://other.org/x")
              local bad = url_resolve("::not a uri::", "rel")
              return abs .. "|" .. bad
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == LuaData.LuaStr("https://other.org/x|rel")) { r.data.toString() }
    }

    @Test
    fun `url_resolve keeps query and fragment`() {
        val r = eval(
            """
            function main()
              return url_resolve("https://example.com/dir/page.html?q=1#frag", "other.html?z=2#top")
            end
            """.trimIndent()
        )
        check(r.error == null) { r.error!! }
        check(r.data == LuaData.LuaStr("https://example.com/dir/other.html?z=2#top")) { r.data.toString() }
    }
}
