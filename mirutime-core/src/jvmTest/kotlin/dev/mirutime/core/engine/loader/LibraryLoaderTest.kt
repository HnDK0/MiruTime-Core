package dev.mirutime.core.engine.loader

import dev.mirutime.core.api.PluginError
import dev.mirutime.core.engine.LuaData
import dev.mirutime.core.engine.LuaSession
import dev.mirutime.core.engine.createLuaSession
import dev.mirutime.core.host.FakeFileSystem
import dev.mirutime.core.network.cache.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.TwoArgFunction

class LibraryLoaderTest {

    private val fs = FakeFileSystem()
    // ONE loader per test (production form: one loader per factory, shared by
    // every plugin source — the Prototype memo is process-wide either way).
    private val loader = LibraryLoader(fs, "libs")
    private val libSource = "return { version = 1 }"
    private val libSha = sha256Hex(libSource.encodeToByteArray())

    @Before
    fun clearProcessMemo() {
        LibraryLoader.clearMemoForTests()
    }

    private fun newSession(
        sourceId: String = "plugin",
        script: String = "function main() return 0 end",
    ): LuaSession {
        val session = createLuaSession(sourceId, script)
        loader.install(session)
        return session
    }

    // Invokes the installed require_lib directly on the session's globals —
    // jvmTest is a friend module of the jvmMain compilation, so the internal
    // globals accessor is visible (same pattern the bindings use internally).
    private fun requireLib(session: LuaSession, id: String, sha: String): LuaValue =
        (session.globals.get("require_lib") as TwoArgFunction)
            .call(LuaValue.valueOf(id), LuaValue.valueOf(sha))

    private fun requireLib(session: LuaSession, id: String): LuaValue =
        (session.globals.get("require_lib") as TwoArgFunction)
            .call(LuaValue.valueOf(id))

    // ── process-wide Prototype memo, per-session tables ──────────────────────

    @Test
    fun `two sessions get isolated tables over one cached prototype`() {
        fs.write("libs/x.lua", libSource.encodeToByteArray())
        val sessionA = newSession(sourceId = "a")
        val tableA = requireLib(sessionA, "x", libSha)

        val sessionB = newSession(sourceId = "b")
        val tableB = requireLib(sessionB, "x", libSha)

        assertNotSame("per-session tables: no LuaTable crosses sessions", tableA, tableB)
        assertEquals(1, loader.memoizedLibCount)
        assertEquals(1, tableA.get("version").toint())
        assertEquals(1, tableB.get("version").toint())
    }

    @Test
    fun `lib state mutated by one session is invisible to another`() {
        fs.write("libs/counter.lua", "local c = { n = 0 } function c.bump() c.n = c.n + 1 return c.n end return c".encodeToByteArray())
        val sha = sha256Hex(fs.read("libs/counter.lua")!!)
        val tableA = requireLib(newSession(sourceId = "a"), "counter", sha)
        val tableB = requireLib(newSession(sourceId = "b"), "counter", sha)

        tableA.get("bump").call()
        tableA.get("bump").call()
        // Isolated libs: session B starts from zero, unaffected by A.
        assertEquals(1, tableB.get("bump").call().toint())
        assertEquals(3, tableA.get("bump").call().toint())
    }

    @Test
    fun `concurrent sessions mutate isolated lib state`() {
        fs.write("libs/counter.lua", "local c = { n = 0 } function c.bump() c.n = c.n + 1 return c.n end return c".encodeToByteArray())
        val sha = sha256Hex(fs.read("libs/counter.lua")!!)
        val bumps = 50
        val gate = java.util.concurrent.CountDownLatch(1)
        var lastA = -1
        var lastB = -1
        // First require runs on the worker threads too, so the atomic
        // check-and-compile path is exercised under race as well.
        val tA = kotlin.concurrent.thread {
            gate.await()
            val table = requireLib(newSession(sourceId = "a"), "counter", sha)
            repeat(bumps) { lastA = table.get("bump").call().toint() }
        }
        val tB = kotlin.concurrent.thread {
            gate.await()
            val table = requireLib(newSession(sourceId = "b"), "counter", sha)
            repeat(bumps) { lastB = table.get("bump").call().toint() }
        }
        gate.countDown()
        tA.join()
        tB.join()
        // A shared table would interleave both counters toward 2N; isolated
        // tables each count exactly their own bumps.
        assertEquals(bumps, lastA)
        assertEquals(bumps, lastB)
    }

    @Test
    fun `same session executes a fresh table per call, prototype cached once`() {
        fs.write("libs/x.lua", libSource.encodeToByteArray())
        val session = newSession()
        val first = requireLib(session, "x", libSha)
        val second = requireLib(session, "x", libSha)
        assertNotSame(first, second)
        assertEquals(1, first.get("version").toint())
        assertEquals(1, second.get("version").toint())
        assertEquals(1, loader.memoizedLibCount)
    }

    @Test
    fun `one-arg form resolves without a sha`() {
        fs.write("libs/x.lua", libSource.encodeToByteArray())
        val session = newSession()
        assertEquals(1, requireLib(session, "x").get("version").toint())
    }

    @Test
    fun `metatable falls back to the loading session globals`() {
        fs.write("libs/x.lua", "return {}".encodeToByteArray())
        val sha = sha256Hex("return {}".encodeToByteArray())
        val sessionA = newSession(sourceId = "a")
        sessionA.globals.set("answer", LuaValue.valueOf(1))

        assertEquals(1, requireLib(sessionA, "x", sha).get("answer").toint())
    }

    @Test
    fun `each session table falls back to its own globals`() {
        fs.write("libs/x.lua", "return {}".encodeToByteArray())
        val sha = sha256Hex("return {}".encodeToByteArray())
        val sessionA = newSession(sourceId = "a")
        sessionA.globals.set("answer", LuaValue.valueOf(1))
        val sessionB = newSession(sourceId = "b")
        sessionB.globals.set("answer", LuaValue.valueOf(2))

        assertEquals(1, requireLib(sessionA, "x", sha).get("answer").toint())
        // A first-loader-bound __index would answer 1 here (stale globals).
        assertEquals(2, requireLib(sessionB, "x", sha).get("answer").toint())
    }

    @Test
    fun `memo does not grow without bound`() {
        val max = LibraryLoader.MAX_MEMOIZED_LIBS
        for (i in 0 until max + 10) {
            val src = "return { version = $i }"
            fs.write("libs/lib$i.lua", src.encodeToByteArray())
            val table = requireLib(newSession(sourceId = "s$i"), "lib$i", sha256Hex(src.encodeToByteArray()))
            assertEquals(i, table.get("version").toint())
        }
        assertTrue(loader.memoizedLibCount <= max)
    }

    // ── sha256 verification ──────────────────────────────────────────────────

    @Test
    fun `sha mismatch is rejected`() {
        fs.write("libs/x.lua", libSource.encodeToByteArray())
        val session = newSession()
        val wrongSha = sha256Hex("evil".encodeToByteArray())
        assertThrows(LuaError::class.java) { requireLib(session, "x", wrongSha) }
    }

    @Test
    fun `unknown lib and invalid id are rejected`() {
        val session = newSession()
        assertThrows(LuaError::class.java) { requireLib(session, "nope", libSha) }
        assertThrows(LuaError::class.java) { requireLib(session, "../evil", libSha) }
        assertThrows(LuaError::class.java) { requireLib(session, "BadId", libSha) }
    }

    @Test
    fun `lib that does not return a table is rejected`() {
        fs.write("libs/x.lua", "return 5".encodeToByteArray())
        val sha = sha256Hex("return 5".encodeToByteArray())
        val session = newSession()
        assertThrows(LuaError::class.java) { requireLib(session, "x", sha) }
    }

    // ── force-reload on sha change ───────────────────────────────────────────

    @Test
    fun `sha change forces a fresh lib table in every session`() {
        fs.write("libs/x.lua", libSource.encodeToByteArray())
        val session = newSession()
        val tableV1 = requireLib(session, "x", libSha)

        val libV2 = "return { version = 2 }"
        fs.write("libs/x.lua", libV2.encodeToByteArray())
        val shaV2 = sha256Hex(libV2.encodeToByteArray())
        val tableV2 = requireLib(session, "x", shaV2)

        assertNotSame(tableV1, tableV2)
        assertEquals(2, tableV2.get("version").toint())
        assertEquals("v2 replaces the stale memo entry", 1, loader.memoizedLibCount)
    }

    @Test
    fun `lib sha change forces reload of plugins using it`() {
        val plugin = """
            function main()
              local d = require_lib("x", "%s")
              return d.version
            end
        """.trimIndent()

        fs.write("libs/x.lua", libSource.encodeToByteArray())
        val sessionV1 = newSession(script = plugin.format(libSha))
        assertEquals(LuaData.LuaNum(1.0), sessionV1.call("main", emptyList()).data)

        // Lib updated on disk → new sha invalidates the old memo.
        val libV2 = "return { version = 2 }"
        fs.write("libs/x.lua", libV2.encodeToByteArray())
        val shaV2 = sha256Hex(libV2.encodeToByteArray())

        // A plugin still pinning the stale sha must fail loudly, not run old code.
        val staleSession = newSession(script = plugin.format(libSha))
        val stale = staleSession.call("main", emptyList())
        assertTrue(stale.error is PluginError.ScriptError)

        // The reloaded plugin (new session, new sha) picks up version 2.
        val reloadedSession = newSession(script = plugin.format(shaV2))
        assertEquals(LuaData.LuaNum(2.0), reloadedSession.call("main", emptyList()).data)
    }
}
