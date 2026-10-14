package dev.mirutime.core.engine.binding

import dev.mirutime.core.engine.LuaSession
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.ZeroArgFunction

// os_time() — Unix time in milliseconds, for cache-busting URLs
// (NoveLA OsTimeFunction: System.currentTimeMillis).
object OsBindings {

    fun install(session: LuaSession) {
        session.globals.set("os_time", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(System.currentTimeMillis().toDouble())
        })
    }
}
