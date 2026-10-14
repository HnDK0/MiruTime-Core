package dev.mirutime.core.engine

import dev.mirutime.core.api.PluginError
import dev.mirutime.core.engine.binding.CryptoBindings
import dev.mirutime.core.engine.binding.JsonBindings
import dev.mirutime.core.engine.binding.LogBindings
import dev.mirutime.core.engine.binding.MiscBindings
import dev.mirutime.core.engine.binding.OsBindings
import dev.mirutime.core.engine.binding.StorageBindings
import dev.mirutime.core.engine.binding.StringBindings
import dev.mirutime.core.engine.binding.UrlBindings
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeKeyValueStore
import dev.mirutime.core.network.PluginUserAgentRegistry
import dev.mirutime.core.network.UserAgentPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Parity tests for the globals added for NoveLA behavioral parity.
class AddedGlobalsBindingsTest {

    private fun eval(code: String, vararg install: (LuaSession) -> Unit): LuaCallResult {
        val session = createLuaSession("eval", code)
        install.forEach { it(session) }
        return session.call("main", emptyList())
    }

    private fun installStringUrl() = { s: LuaSession ->
        StringBindings.install(s)
        UrlBindings.install(s)
    }

    // ── URL ──────────────────────────────────────────────────────────────────

    @Test
    fun `url_encode form-encodes utf8`() {
        val r = eval(
            """
            function main()
              return url_encode("a b&c")
            end
            """.trimIndent(),
            installStringUrl(),
        )
        check(r.error == null) { r.error!! }
        assertEquals(LuaData.LuaStr("a+b%26c"), r.data)
    }

    @Test
    fun `url_encode_charset defaults to utf8`() {
        val r = eval(
            """
            function main()
              return url_encode_charset("a b", "UTF-8")
            end
            """.trimIndent(),
            installStringUrl(),
        )
        check(r.error == null) { r.error!! }
        assertEquals(LuaData.LuaStr("a+b"), r.data)
    }

    // ── String utils ─────────────────────────────────────────────────────────

    @Test
    fun `string_trim and string_clean`() {
        val r = eval(
            """
            function main()
              return string_trim("  x  ") .. "|" .. string_clean("  Hello   World  ")
            end
            """.trimIndent(),
            installStringUrl(),
        )
        check(r.error == null) { r.error!! }
        assertEquals(LuaData.LuaStr("x|Hello World"), r.data)
    }

    @Test
    fun `string_starts_with and string_ends_with`() {
        val r = eval(
            """
            function main()
              local a = string_starts_with("hello", "he")
              local b = string_ends_with("hello", "lo")
              local c = string_starts_with("hello", "xx")
              return (a and "1" or "0") .. (b and "1" or "0") .. (c and "1" or "0")
            end
            """.trimIndent(),
            installStringUrl(),
        )
        check(r.error == null) { r.error!! }
        assertEquals(LuaData.LuaStr("110"), r.data)
    }

    @Test
    fun `string_normalize applies nfkc`() {
        val r = eval(
            """
            function main()
              -- U+FB01 LATIN SMALL LIGATURE FI, NFKC -> "fi"
              return string_normalize("ﬁn")
            end
            """.trimIndent(),
            installStringUrl(),
        )
        check(r.error == null) { r.error!! }
        assertEquals(LuaData.LuaStr("fin"), r.data)
    }

    @Test
    fun `string_split returns one-based array`() {
        val r = eval(
            """
            function main()
              local t = string_split("a,b,c", ",")
              return #t .. ":" .. t[2]
            end
            """.trimIndent(),
            installStringUrl(),
        )
        check(r.error == null) { r.error!! }
        assertEquals(LuaData.LuaStr("3:b"), r.data)
    }

    @Test
    fun `unescape_unicode decodes escapes`() {
        val r = eval(
            """
            function main()
              return unescape_unicode("\\u0041\\u0416")
            end
            """.trimIndent(),
            installStringUrl(),
        )
        check(r.error == null) { r.error!! }
        assertEquals(LuaData.LuaStr("A\u0416"), r.data)
    }

    // ── Log / OS ─────────────────────────────────────────────────────────────

    @Test
    fun `log_info and log_error are callable sinks`() {
        val r = eval(
            """
            function main()
              log_info("hello")
              log_error("boom")
              return "ok"
            end
            """.trimIndent(),
            { LogBindings.install(it) },
        )
        check(r.error == null) { r.error!! }
        assertEquals(LuaData.LuaStr("ok"), r.data)
    }

    @Test
    fun `os_time returns epoch millis`() {
        val r = eval(
            """
            function main()
              return os_time() > 0 and "pos" or "neg"
            end
            """.trimIndent(),
            { OsBindings.install(it) },
        )
        check(r.error == null) { r.error!! }
        assertEquals(LuaData.LuaStr("pos"), r.data)
    }

    // ── JSON / base64 ────────────────────────────────────────────────────────

    @Test
    fun `json_stringify serializes a table`() {
        val r = eval(
            """
            function main()
              return json_stringify({ a = 1, b = "x" })
            end
            """.trimIndent(),
            { CryptoBindings.install(it) },
            { JsonBindings.install(it) },
        )
        check(r.error == null) { r.error!! }
        assertEquals(LuaData.LuaStr("""{"a":1,"b":"x"}"""), r.data)
    }

    @Test
    fun `base64_decode returns utf8 string and nil on invalid`() {
        val r = eval(
            """
            function main()
              local ok = base64_decode("aGVsbG8=") or "nil"
              local bad = base64_decode("!!!") or "nil"
              return ok .. "|" .. bad
            end
            """.trimIndent(),
            { CryptoBindings.install(it) },
            { MiscBindings.install(it) },
        )
        check(r.error == null) { r.error!! }
        assertEquals(LuaData.LuaStr("hello|nil"), r.data)
    }

    // ── Misc ─────────────────────────────────────────────────────────────────

    @Test
    fun `detect_pagination finds next link`() {
        val r = eval(
            """
            function main()
              local p = detect_pagination("<div><a href='/page/2'>Next</a></div>")
              return (p.hasNext and "T" or "F") .. ":" .. (p.next_url or "nil")
            end
            """.trimIndent(),
            { MiscBindings.install(it) },
        )
        check(r.error == null) { r.error!! }
        assertEquals(LuaData.LuaStr("T:/page/2"), r.data)
    }

    @Test
    fun `detect_pagination without next link`() {
        val r = eval(
            """
            function main()
              local p = detect_pagination("<div><a href='/a'>Prev</a></div>")
              return (p.hasNext and "T" or "F") .. ":" .. (p.next_url or "nil")
            end
            """.trimIndent(),
            { MiscBindings.install(it) },
        )
        check(r.error == null) { r.error!! }
        assertEquals(LuaData.LuaStr("F:nil"), r.data)
    }

    @Test
    fun `get_user_agent returns the source default`() {
        PluginUserAgentRegistry.clear()
        val r = eval(
            """
            function main()
              return get_user_agent()
            end
            """.trimIndent(),
            { MiscBindings.install(it) },
        )
        check(r.error == null) { r.error!! }
        assertEquals(LuaData.LuaStr(UserAgentPresets.DEFAULT), r.data)
    }

    // ── show_error 3-arg ─────────────────────────────────────────────────────

    @Test
    fun `show_error accepts optional authUrl`() {
        val session = createLuaSession(
            "src-1",
            """
            function main()
              show_error("Login required", "Please sign in", "https://auth.example/login")
              return "unreachable"
            end
            """.trimIndent(),
        )
        StorageBindings(FakeKeyValueStore(), FakeCookieStore(), "src-1").install(session)
        val r = session.call("main", emptyList())
        assertEquals(
            PluginError.UserMessage("Login required", "Please sign in", "https://auth.example/login"),
            r.error,
        )
        assertNull(r.data)
    }

    @Test
    fun `show_error two-arg form keeps authUrl null`() {
        val session = createLuaSession(
            "src-1",
            """
            function main()
              show_error("T", "M")
              return nil
            end
            """.trimIndent(),
        )
        StorageBindings(FakeKeyValueStore(), FakeCookieStore(), "src-1").install(session)
        val r = session.call("main", emptyList())
        assertEquals(PluginError.UserMessage("T", "M", null), r.error)
        assertTrue((r.error as PluginError.UserMessage).authUrl == null)
    }
}
