package dev.mirutime.core.engine.loader

import dev.mirutime.core.engine.LuaSession
import dev.mirutime.core.host.FileSystem
import dev.mirutime.core.network.cache.sha256Hex
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaClosure
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Prototype
import org.luaj.vm2.lib.TwoArgFunction
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap

// require_lib(libId [, expectedSha256]): loads shared Lua libraries.
//
// Per-session tables over a shared IMMUTABLE compiled-Prototype cache.
// NoveLA memoizes the executed TABLE process-wide (LuaSourceLoader.kt:144-182:
// every session gets the SAME table, __index bound once to the first
// session's globals) — but sessions are locked independently (one mutex per
// LuaPluginSource, calls hop to the multi-threaded Dispatchers.IO), so two
// plugins can touch that one LuaTable concurrently, and LuaJ tables are not
// thread-safe. Here only the compiled Prototype (immutable after compile) is
// shared: each require_lib executes a fresh LuaClosure in the CALLING
// session's globals and returns its own table, with __index pointing at that
// same session. Lib-level mutable state never crosses sessions and there is
// no shared mutable Lua value left to race on; the compile cost is still paid
// once per lib version.
//
// NoveLA keys the memo by mtime/len; FileSystem exposes no mtime, so the
// content sha256 is the key instead (strictly stronger: same invalidation,
// no aliasing on equal size). The optional 2nd-arg sha is still verified
// against the file when a plugin passes it.
// DI: FileSystem + libDir, like ScriptLoader/NetworkBindings.
class LibraryLoader(
    private val fileSystem: FileSystem,
    private val libDir: String = "lua_libs",
) {
    // Test hook: how many libIds hold a cached Prototype process-wide.
    internal val memoizedLibCount: Int get() = memoized.size

    fun install(session: LuaSession) {
        // TwoArgFunction (not OneArgFunction): the 1-arg NoveLA/corpus form
        // arrives as call(id, NIL); optjstring(null) tolerates the missing
        // second argument.
        session.globals.set("require_lib", object : TwoArgFunction() {
            override fun call(idArg: LuaValue, shaArg: LuaValue): LuaValue =
                requireLib(
                    session.globals,
                    idArg.checkjstring(),
                    // NIL when the plugin used the 1-arg form (LibFunction.call ->
                    // call(arg, NIL)); optjstring(null) also tolerates an explicit nil.
                    shaArg.optjstring(null),
                )
        })
    }

    private fun requireLib(
        globals: Globals,
        id: String,
        expectedSha256: String?,
    ): LuaValue {
        if (!ID_PATTERN.matches(id)) {
            throw LuaError("require_lib: invalid library id: $id")
        }
        val bytes = fileSystem.read("$libDir/$id.lua")
            ?: throw LuaError("require_lib: library not found: $id (update the catalog)")
        val actual = sha256Hex(bytes)
        // A passed sha is optional but, when present, must match the file.
        if (expectedSha256 != null && !actual.equals(expectedSha256, ignoreCase = true)) {
            throw LuaError("require_lib: sha256 mismatch for $id: got $actual")
        }
        val source = bytes.decodeToString()
        // Bound (corpus ceiling: external-sources/libs ships 49 lib files, so
        // 64 leaves headroom; overflow clears instead of evicting one entry —
        // safe because entries are immutable and sha-keyed, worst case is a
        // recompile). Benign under race: entries are immutable, so any
        // interleaving stays correct and only costs a recompile.
        if (memoized.size >= MAX_MEMOIZED_LIBS && !memoized.containsKey(id)) memoized.clear()
        // Atomic check-and-compile per key: concurrent first loads of the same
        // lib compile once (no check-then-act double-compile), and a mapping
        // function that throws records nothing — a lib that fails to compile
        // is retried, not cached. Execution below is per-session by design.
        val entry = memoized.compute(id) { _, existing ->
            if (existing != null && existing.sha256 == actual) {
                existing
            } else {
                MemoEntry(actual, compileLib(globals, source, id))
            }
        } ?: throw LuaError("require_lib: failed to load $id")
        // Fresh closure per call: the shared Prototype executes in the CALLING
        // session's globals, so each session gets its own result table.
        val result = try {
            LuaClosure(entry.prototype, globals).call()
        } catch (e: Exception) {
            throw LuaError("require_lib: failed to load $id: ${e.message}")
        }
        if (!result.istable()) {
            throw LuaError("require_lib: $id must return a table")
        }
        val table = result.checktable()
        // Metatable points at the CALLING session's globals: `lib.foo` for an
        // unknown foo falls through to this session's sandbox globals.
        table.setmetatable(LuaTable().also { it.set(LuaValue.INDEX, globals) })
        return table
    }

    // Compiles in the requesting session's globals (only they carry the
    // installed LuaC compiler): the Prototype depends on the source alone,
    // and LuaC.instance is explicitly sharable with per-call compile state,
    // so concurrent compiles of different libs are safe; same-lib compiles
    // are serialized by compute() above.
    private fun compileLib(globals: Globals, source: String, id: String): Prototype = try {
        globals.compilePrototype(StringReader(source), "lib/$id.lua")
    } catch (e: Exception) {
        throw LuaError("require_lib: failed to load $id: ${e.message}")
    }

    private data class MemoEntry(val sha256: String, val prototype: Prototype)

    companion object {
        // Mirrors NoveLA's require_lib id rule: path-safe lowercase token.
        private val ID_PATTERN = Regex("[a-z0-9_]+")

        // Process-wide Prototype cache: LuaPluginSourceFactory owns one
        // LibraryLoader for all plugins, so an instance field would still be
        // shared — the static memo is deliberate, and safe now that only
        // immutable Prototypes cross sessions.
        private val memoized = ConcurrentHashMap<String, MemoEntry>()

        // S-NEW1 bound: entries are compiled Prototypes (KBs), never tables.
        internal const val MAX_MEMOIZED_LIBS = 64

        // Test hook: drop all cached Prototypes between tests (JUnit creates a
        // new test-class instance per test, but the memo outlives them).
        internal fun clearMemoForTests() {
            memoized.clear()
        }
    }
}
