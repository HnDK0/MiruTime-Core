package dev.mirutime.core.engine

import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.JsePlatform

// Sandboxed Globals for one plugin script. Mirrors NoveLA's
// createLuaSandboxGlobals (LuaSourceLoader.kt) with a cleanup: everything
// that gives RCE, filesystem or VM access is removed; os is narrowed to a
// time/date/clock/difftime whitelist. string/table/math/utf8/base stay for plugins.
internal fun createSandboxGlobals(): org.luaj.vm2.Globals {
    val globals = JsePlatform.standardGlobals()
    // Code loading / process / filesystem / Java interop — gone entirely.
    globals.set("luajava", LuaValue.NIL)
    globals.set("io", LuaValue.NIL)
    globals.set("load", LuaValue.NIL)
    globals.set("loadfile", LuaValue.NIL)
    globals.set("loadstring", LuaValue.NIL)
    globals.set("dofile", LuaValue.NIL)
    globals.set("require", LuaValue.NIL)
    globals.set("package", LuaValue.NIL)
    globals.set("debug", LuaValue.NIL)
    // coroutine stays: NoveLA removes only luajava/io/load*/require/package/
    // debug (LuaSourceLoader.kt:1417-1428), and no corpus plugin abuses it.
    // os: whitelist time/date plus clock/difftime (NoveLA keeps the full os
    // minus execute/exit/getenv/rename/remove/tmpname); a whitelist cannot
    // drift when LuaJ adds new os functions.
    (globals.get("os") as? LuaTable)?.apply {
        val keep = setOf("time", "date", "clock", "difftime")
        for (key in keys()) {
            if (key.tojstring() !in keep) set(key, LuaValue.NIL)
        }
    }
    return globals
}
