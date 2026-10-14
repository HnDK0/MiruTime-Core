package dev.mirutime.core.engine

import dev.mirutime.core.api.PluginError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SandboxTest {

    @Test
    fun dangerousGlobalsAreRemoved() {
        val session = createLuaSession(
            "test-dangerous",
            """
            function probe()
                return {
                    os_execute = os.execute == nil,
                    os_getenv = os.getenv == nil,
                    os_remove = os.remove == nil,
                    io_open = io == nil,
                    require_fn = require == nil,
                    luajava = luajava == nil,
                    load_fn = load == nil,
                    loadfile_fn = loadfile == nil,
                    dofile_fn = dofile == nil,
                    package_tbl = package == nil,
                    debug_tbl = debug == nil,
                }
            end
            """.trimIndent(),
        )
        val result = session.call("probe", emptyList())
        session.dispose()
        assertEquals(null, result.error)
        val map = assertIs<LuaData.LuaMap>(result.data)
        assertEquals(11, map.entries.size)
        for ((key, value) in map.entries) {
            assertEquals(LuaData.LuaBool(true), value, "$key must be blocked")
        }
    }

    @Test
    fun coroutineAndOsClockPresent() {
        // NoveLA keeps coroutine and the full os minus execute/exit/getenv/
        // rename/remove/tmpname (LuaSourceLoader.kt:1417-1438).
        val session = createLuaSession(
            "test-coroutine-os",
            """
            function probe()
                local co = coroutine.create(function() coroutine.yield(7) end)
                local _, v = coroutine.resume(co)
                return {
                    co_ok = v == 7,
                    clock_fn = type(os.clock) == "function",
                    difftime_fn = type(os.difftime) == "function",
                    time_fn = type(os.time) == "function",
                    date_fn = type(os.date) == "function",
                }
            end
            """.trimIndent(),
        )
        val result = session.call("probe", emptyList())
        session.dispose()
        assertEquals(null, result.error)
        val map = assertIs<LuaData.LuaMap>(result.data)
        assertEquals(5, map.entries.size)
        for ((key, value) in map.entries) {
            assertEquals(LuaData.LuaBool(true), value, "$key must be present")
        }
    }

    @Test
    fun callingBlockedOsExecuteFails() {
        val session = createLuaSession(
            "test-blocked-call",
            """
            function tryExec()
                -- pcall(nil) must fail: os.execute was removed, not neutered.
                local ok = pcall(os.execute, "echo pwned")
                return ok
            end
            """.trimIndent(),
        )
        val result = session.call("tryExec", emptyList())
        session.dispose()
        assertEquals(null, result.error)
        assertEquals(LuaData.LuaBool(false), result.data)
    }

    @Test
    fun osTimeWorks() {
        val session = createLuaSession("test-os-time", "function now() return os.time() end")
        val result = session.call("now", emptyList())
        session.dispose()
        assertEquals(null, result.error)
        val num = assertIs<LuaData.LuaNum>(result.data)
        assertTrue(num.value > 0, "os.time() must be positive, got ${num.value}")
    }

    @Test
    fun sessionsDoNotShareGlobals() {
        val a = createLuaSession("iso-a", "function mark() _G.shared = 42 end")
        val b = createLuaSession("iso-b", "function read() return _G.shared end")

        assertEquals(null, a.call("mark", emptyList()).error)
        val seen = b.call("read", emptyList())
        a.dispose()
        b.dispose()

        assertEquals(null, seen.error)
        assertEquals(LuaData.LuaNil, seen.data, "session B must not see session A globals")
    }

    @Test
    fun callSuccessReturnsDataWithoutError() {
        val session = createLuaSession("test-add", "function add(a, b) return a + b end")
        val result = session.call("add", listOf(LuaArg.ArgNum(2.0), LuaArg.ArgNum(40.0)))
        session.dispose()
        assertEquals(null, result.error)
        assertEquals(LuaData.LuaNum(42.0), result.data)
    }

    @Test
    fun missingFunctionIsScriptError() {
        val session = createLuaSession("test-missing", "function f() return 1 end")
        val result = session.call("missing", emptyList())
        session.dispose()
        assertEquals(null, result.data)
        assertIs<PluginError.ScriptError>(result.error)
    }

    @Test
    fun luaRuntimeErrorIsScriptError() {
        val session = createLuaSession("test-boom", "function boom() error('kaput') end")
        val result = session.call("boom", emptyList())
        session.dispose()
        assertEquals(null, result.data)
        assertIs<PluginError.ScriptError>(result.error)
    }

    @Test
    fun argumentConversionRoundTrip() {
        val session = createLuaSession(
            "test-args",
            """
            function echo(v) return v end
            function sum(t)
                local n = 0
                for _, x in ipairs(t) do n = n + x end
                return n
            end
            function greet(t) return t.name .. "-" .. tostring(t.count) end
            """.trimIndent(),
        )

        assertEquals(LuaData.LuaStr("hi"), session.call("echo", listOf(LuaArg.ArgStr("hi"))).data)
        assertEquals(LuaData.LuaNum(1.5), session.call("echo", listOf(LuaArg.ArgNum(1.5))).data)
        assertEquals(LuaData.LuaBool(true), session.call("echo", listOf(LuaArg.ArgBool(true))).data)
        assertEquals(
            LuaData.LuaNum(6.0),
            session.call(
                "sum",
                listOf(LuaArg.ArgList(listOf(LuaArg.ArgNum(1.0), LuaArg.ArgNum(2.0), LuaArg.ArgNum(3.0)))),
            ).data,
        )
        assertEquals(
            LuaData.LuaStr("a-7"),
            session.call(
                "greet",
                listOf(
                    LuaArg.ArgMap(
                        mapOf("name" to LuaArg.ArgStr("a"), "count" to LuaArg.ArgNum(7.0)),
                    ),
                ),
            ).data,
        )
        session.dispose()
    }

    @Test
    fun returnTableConversion() {
        val session = createLuaSession(
            "test-tables",
            """
            function nested()
                return { name = "x", items = {10, 20}, flag = true }
            end
            function arr() return {"a", "b"} end
            """.trimIndent(),
        )

        val nested = assertIs<LuaData.LuaMap>(session.call("nested", emptyList()).data)
        assertEquals(LuaData.LuaStr("x"), nested.entries["name"])
        assertEquals(
            LuaData.LuaList(listOf(LuaData.LuaNum(10.0), LuaData.LuaNum(20.0))),
            nested.entries["items"],
        )
        assertEquals(LuaData.LuaBool(true), nested.entries["flag"])
        assertEquals(
            LuaData.LuaList(listOf(LuaData.LuaStr("a"), LuaData.LuaStr("b"))),
            session.call("arr", emptyList()).data,
        )
        session.dispose()
    }
}
