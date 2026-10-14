package dev.mirutime.core.engine.binding

import dev.mirutime.core.engine.LuaSession
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction

// Diagnostic sinks for plugin scripts. NoveLA routes these to Timber
// (log_info -> i, log_error -> e); the shared core has no Timber, so info
// goes to stdout and error to stderr. A non-string argument defaults to "".
object LogBindings {

    fun install(session: LuaSession) {
        val globals = session.globals
        globals.set("log_info", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                println("Lua: ${arg.optjstring("")}")
                return LuaValue.NIL
            }
        })
        globals.set("log_error", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                System.err.println("Lua: ${arg.optjstring("")}")
                return LuaValue.NIL
            }
        })
    }
}
