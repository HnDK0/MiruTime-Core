package dev.mirutime.core.engine

import dev.mirutime.core.api.PluginError
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

// JVM implementation of the engine facade on LuaJ. Each session owns a
// private sandboxed Globals; the script chunk runs at creation so its
// functions become callable by name. No ThreadLocal context (NoveLA
// precedent): sourceId is an explicit session field.
actual fun createLuaSession(sourceId: String, script: String): LuaSession {
    val globals = createSandboxGlobals()
    globals.load(script, "@$sourceId").call()
    return LuaSession(sourceId, globals)
}

// Expect class declares no constructor — this one is free-form; only the
// members listed in the expect class are actualized.
actual class LuaSession internal constructor(
    actual val sourceId: String,
    // Internal: bindings (NetworkBindings) install bridge functions here.
    internal val globals: Globals,
) {
    // Parked by show_error (StorageBindings); bridged into LuaCallResult.error
    // by the next call() and then cleared. No ThreadLocal: the session is the
    // unit of serialization (single-threaded use).
    internal var pendingUserError: PluginError? = null

    actual fun call(fnName: String, args: List<LuaArg>): LuaCallResult {
        val fn = globals.get(fnName)
        if (fn.isnil()) {
            return LuaCallResult(null, PluginError.ScriptError("'$fnName' is not defined"))
        }
        if (!fn.isfunction()) {
            return LuaCallResult(null, PluginError.ScriptError("'$fnName' is not a function"))
        }
        // Clear first: a non-Exception Throwable (e.g. StackOverflowError)
        // bypasses both catches and would leak the pending message otherwise.
        pendingUserError = null
        return try {
            val varargs = LuaValue.varargsOf(args.map { it.toLuaValue() }.toTypedArray())
            val data = fn.invoke(varargs).arg1().toLuaData()
            // show_error wins over the return value: it is a user-facing signal.
            val pending = pendingUserError
            pendingUserError = null
            if (pending != null) LuaCallResult(null, pending) else LuaCallResult(data, null)
        } catch (e: LuaError) {
            // Ethalon NoveLA (LuaSourceAdapter.kt:544-547): a parked user
            // message outranks the generic script error.
            takePendingError() ?: LuaCallResult(null, PluginError.ScriptError(e.message ?: "lua error in '$fnName'"))
        } catch (e: Exception) {
            takePendingError() ?: LuaCallResult(
                null,
                PluginError.ScriptError(e.message ?: e::class.simpleName ?: "error in '$fnName'"),
            )
        }
    }

    private fun takePendingError(): LuaCallResult? {
        val pending = pendingUserError ?: return null
        pendingUserError = null
        return LuaCallResult(null, pending)
    }

    // LuaJ 3.0.1 Globals has no close()/destroy() (that API arrived later):
    // dropping the last reference lets the Lua GC reclaim the state. No file
    // handles can linger — io was removed from the sandbox.
    // ceiling: callers should stop using the session after dispose; a hard
    // kill would need a newer LuaJ or an explicit state reset.
    actual fun dispose() = Unit
}

// ── Boundary conversions: LuaArg -> LuaValue, LuaValue -> LuaData ────────────

private fun LuaArg.toLuaValue(): LuaValue = when (this) {
    LuaArg.ArgNil -> LuaValue.NIL
    is LuaArg.ArgBool -> LuaValue.valueOf(value)
    is LuaArg.ArgNum -> LuaValue.valueOf(value)
    is LuaArg.ArgStr -> LuaValue.valueOf(value)
    is LuaArg.ArgList -> LuaValue.listOf(items.map { it.toLuaValue() }.toTypedArray())
    is LuaArg.ArgMap -> LuaTable().also { table ->
        entries.forEach { (key, arg) -> table.set(key, arg.toLuaValue()) }
    }
}

private fun LuaValue.toLuaData(): LuaData = when (type()) {
    // Discriminate by type(): LuaJ reports isnumber()=true for numeric
    // strings ("2735") and isstring()=true for numbers — both coercions
    // would misclassify at the boundary.
    LuaValue.TNIL -> LuaData.LuaNil
    LuaValue.TBOOLEAN -> LuaData.LuaBool(toboolean())
    LuaValue.TNUMBER -> LuaData.LuaNum(todouble())
    LuaValue.TSTRING -> LuaData.LuaStr(tojstring())
    LuaValue.TTABLE -> checktable().toLuaData()
    // Functions/userdata/threads have no boundary representation.
    else -> LuaData.LuaNil
}

private fun LuaTable.toLuaData(): LuaData {
    val keys = keys()
    // Sequential integer keys 1..n -> list, anything else -> string map.
    // ceiling: exotic numeric keys (e.g. 2.5) may misclassify as a list;
    // plugin tables are JSON-shaped, revisit if that ever bites.
    val isList = keys.all { it.isnumber() && it.toint() in 1..keys.size }
    return if (isList) {
        LuaData.LuaList((1..keys.size).map { idx -> get(idx).toLuaData() })
    } else {
        LuaData.LuaMap(keys.associate { key -> key.tojstring() to get(key).toLuaData() })
    }
}
