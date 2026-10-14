package dev.mirutime.core.engine

import dev.mirutime.core.api.PluginError
import dev.mirutime.core.engine.binding.StorageBindings
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeKeyValueStore
import dev.mirutime.core.host.CookieStore
import dev.mirutime.core.host.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageBindingsTest {

    private fun session(
        sourceId: String,
        script: String,
        kv: KeyValueStore = FakeKeyValueStore(),
        cs: CookieStore = FakeCookieStore(),
    ): LuaSession {
        val s = createLuaSession(sourceId, script)
        StorageBindings(kv, cs, sourceId).install(s)
        return s
    }

    // ── localStorage: 2-arg, host-namespaced, nil-if-absent ──────────────────
    // NoveLA LuaSourceLoader.kt:956-970 + plugin guide: plugins read the
    // WebView-extracted map via get_localStorage(url, key).

    @Test
    fun `localStorage roundtrip through host namespace`() {
        val store = FakeKeyValueStore()
        val s1 = session(
            "src-1",
            """function main() set_localStorage("https://a.example/page", "token", "abc123") end""",
            kv = store,
        )
        check(s1.call("main", emptyList()).error == null)
        // Namespace is the host, not the sourceId (NoveLA host-based scheme).
        assertEquals("abc123", store.get("a.example", "token"))
        val s2 = session(
            "src-1",
            """function main() return get_localStorage("https://a.example/other", "token") end""",
            kv = store,
        )
        val r = s2.call("main", emptyList())
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaStr("abc123"), r.data)
    }

    @Test
    fun `localStorage missing key returns nil`() {
        val s = session("src-1", """function main() return get_localStorage("https://a.example/", "nope") end""")
        val r = s.call("main", emptyList())
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaNil, r.data)
    }

    @Test
    fun `localStorage isolates hosts`() {
        val store = FakeKeyValueStore()
        val a = session(
            "src-1",
            """function main() set_localStorage("https://a.example/", "k", "va") end""",
            kv = store,
        )
        check(a.call("main", emptyList()).error == null)
        val b = session(
            "src-1",
            """function main() return get_localStorage("https://b.example/", "k") end""",
            kv = store,
        )
        val r = b.call("main", emptyList())
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaNil, r.data)
    }

    @Test
    fun `localStorage accepts a bare host as first arg`() {
        val store = FakeKeyValueStore()
        val a = session(
            "src-1",
            """function main() set_localStorage("a.example", "k", "v") end""",
            kv = store,
        )
        check(a.call("main", emptyList()).error == null)
        val b = session(
            "src-1",
            """function main() return get_localStorage("https://a.example/x", "k") end""",
            kv = store,
        )
        val r = b.call("main", emptyList())
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaStr("v"), r.data)
    }

    // ── preferences: GLOBAL namespace, nil-if-absent ─────────────────────────
    // NoveLA LuaSourceLoader.kt:915-926: one "lua_preferences" file shared by
    // every plugin — cross-plugin keys work.

    @Test
    fun `preference roundtrip in the global namespace`() {
        val store = FakeKeyValueStore()
        val a = session(
            "src-a",
            """function main() set_preference("theme", "dark") end""",
            kv = store,
        )
        check(a.call("main", emptyList()).error == null)
        assertEquals("dark", store.get("lua_preferences", "theme"))
        val b = session(
            "src-a",
            """function main() return get_preference("theme") end""",
            kv = store,
        )
        val r = b.call("main", emptyList())
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaStr("dark"), r.data)
    }

    @Test
    fun `preferences are shared across sourceIds`() {
        val store = FakeKeyValueStore()
        val a = session(
            "src-a",
            """function main() set_preference("lang", "ru") end""",
            kv = store,
        )
        check(a.call("main", emptyList()).error == null)
        // Another plugin reads the same global key (corpus: cross-plugin keys).
        val b = session(
            "src-b",
            """function main() return get_preference("lang") end""",
            kv = store,
        )
        val r = b.call("main", emptyList())
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaStr("ru"), r.data)
    }

    @Test
    fun `preference missing key returns nil`() {
        val s = session("src-a", """function main() return get_preference("nope") end""")
        val r = s.call("main", emptyList())
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaNil, r.data)
    }

    // ── cookies: CookieStore round-trip ──────────────────────────────────────

    @Test
    fun `cookies roundtrip through CookieStore bridge`() {
        val cs = FakeCookieStore()
        val s = session(
            "src-1",
            """
            function main()
              local jar = { sid = "42", theme = "dark" }
              set_cookies("https://example.com/chapter/1", jar)
              local got = get_cookies("https://example.com/other")
              return got.sid .. "|" .. got.theme
            end
            """.trimIndent(),
            cs = cs,
        )
        val r = s.call("main", emptyList())
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaStr("42|dark"), r.data)
    }

    @Test
    fun `get_cookies for host without cookies returns empty table`() {
        val cs = FakeCookieStore()
        val s = session(
            "src-1",
            """
            function main()
              local t = get_cookies("https://empty.example/")
              local n = 0
              for _ in pairs(t) do n = n + 1 end
              return n
            end
            """.trimIndent(),
            cs = cs,
        )
        val r = s.call("main", emptyList())
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaNum(0.0), r.data)
    }

    // ── show_error: pending UserMessage for the current call ─────────────────

    @Test
    fun `show_error surfaces UserMessage in current call result`() {
        val s = session(
            "src-1",
            """
            function main()
              show_error("Login required", "Please sign in")
              return "unreachable"
            end
            """.trimIndent(),
        )
        val r = s.call("main", emptyList())
        assertEquals(PluginError.UserMessage("Login required", "Please sign in"), r.error)
        assertNull(r.data)
    }

    @Test
    fun `show_error does not leak into the next call`() {
        val s = session(
            "src-1",
            """
            local fail = true
            function main()
              if fail then
                fail = false
                show_error("Boom", "once")
                return nil
              end
              return "clean"
            end
            """.trimIndent(),
        )
        val first = s.call("main", emptyList())
        assertEquals(PluginError.UserMessage("Boom", "once"), first.error)
        val second = s.call("main", emptyList())
        check(second.error == null) { second.error!!.toString() }
        assertEquals(LuaData.LuaStr("clean"), second.data)
    }

    @Test
    fun `show_error survives a subsequent lua error`() {
        val s = session(
            "src-1",
            """
            function main()
              show_error("T", "msg")
              error("boom")
            end
            """.trimIndent(),
        )
        val r = s.call("main", emptyList())
        assertEquals(PluginError.UserMessage("T", "msg"), r.error)
        assertNull(r.data)
    }

    @Test
    fun `call without show_error keeps normal result`() {
        val s = session("src-1", """function main() return "fine" end""")
        val r = s.call("main", emptyList())
        check(r.error == null) { r.error!!.toString() }
        assertEquals(LuaData.LuaStr("fine"), r.data)
        assertTrue(r.error == null)
    }
}
