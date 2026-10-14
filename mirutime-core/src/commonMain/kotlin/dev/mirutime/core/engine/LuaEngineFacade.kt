package dev.mirutime.core.engine

import dev.mirutime.core.api.PluginError

// Sandboxed Lua session factory: one isolated Globals per plugin script.
// The implementation lives in jvmMain (LuaJ); commonMain only declares the
// contract — no LuaJ types cross this boundary.
expect fun createLuaSession(sourceId: String, script: String): LuaSession

// One plugin script loaded into its own sandbox. Not thread-safe: use a
// session from a single coroutine/thread at a time.
expect class LuaSession {
    val sourceId: String

    // Calls a global Lua function by name. Errors (missing function, Lua
    // runtime error) come back as LuaCallResult.error, not as exceptions.
    fun call(fnName: String, args: List<LuaArg>): LuaCallResult

    // Releases the session; the caller must not call() afterwards. JVM impl
    // (LuaJ 3.0.1) has no hard close — GC reclaims the state.
    fun dispose()
}

// Input arguments Kotlin -> Lua.
sealed class LuaArg {
    data object ArgNil : LuaArg()
    data class ArgBool(val value: Boolean) : LuaArg()
    data class ArgNum(val value: Double) : LuaArg()
    data class ArgStr(val value: String) : LuaArg()
    data class ArgList(val items: List<LuaArg>) : LuaArg()
    data class ArgMap(val entries: Map<String, LuaArg>) : LuaArg()
}

// Output values Lua -> Kotlin, converted at the session boundary.
// LuaValue never leaves the session.
sealed class LuaData {
    data object LuaNil : LuaData()
    data class LuaBool(val value: Boolean) : LuaData()
    data class LuaNum(val value: Double) : LuaData()
    data class LuaStr(val value: String) : LuaData()
    data class LuaList(val items: List<LuaData>) : LuaData()
    data class LuaMap(val entries: Map<String, LuaData>) : LuaData()
}

// Result of LuaSession.call: exactly one of data/error is non-null.
data class LuaCallResult(val data: LuaData?, val error: PluginError?)
