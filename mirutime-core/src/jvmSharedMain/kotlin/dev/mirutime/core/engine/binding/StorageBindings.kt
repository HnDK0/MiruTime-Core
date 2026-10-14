package dev.mirutime.core.engine.binding

import dev.mirutime.core.api.PluginError
import dev.mirutime.core.engine.LuaSession
import dev.mirutime.core.host.CookieStore
import dev.mirutime.core.host.KeyValueStore
import kotlinx.coroutines.runBlocking
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.TwoArgFunction

// Lua -> host storage bridge: cookies (CookieStore), localStorage and
// preferences (KeyValueStore), plus show_error signaling.
//
// NoveLA parity (LuaSourceLoader.kt:915-926, 956-970):
// - get_localStorage(hostOrUrl, key) is 2-arg and HOST-namespaced (NoveLA
//   stores one JSON map per host in "lua_localStorage", filled by the WebView;
//   the KeyValueStore namespace IS the host here). Absent host/key → NIL
//   (plugin guide: "returns nil if key not found").
// - get/set_preference(key [, value]) are GLOBAL under "lua_preferences"
//   (NoveLA's luaPrefs file) — cross-plugin keys work. Absent key → NIL.
// set_localStorage(hostOrUrl, key, value) has no NoveLA counterpart (only the
// WebView writes there); it is kept as the 3-arg mirror of the getter so the
// value stays readable through get_localStorage.
// All dependencies are injected via constructor (DI), like NetworkBindings.
class StorageBindings(
    private val keyValueStore: KeyValueStore,
    private val cookieStore: CookieStore,
    private val sourceId: String,
) {
    fun install(session: LuaSession) {
        val globals = session.globals

        globals.set("get_cookies", object : OneArgFunction() {
            override fun call(url: LuaValue): LuaValue {
                val cookies = runBlocking { cookieStore.get(url.checkjstring()) }
                return LuaTable().also { t -> cookies.forEach { c -> t.set(c.name, c.value) } }
            }
        })
        // CookieStore's write API takes raw Set-Cookie headers; the Lua side
        // passes a name→value table, so we rebuild "name=value" headers.
        globals.set("set_cookies", object : TwoArgFunction() {
            override fun call(url: LuaValue, cookiesMap: LuaValue): LuaValue {
                val table = cookiesMap.checktable()
                val headers = table.keys().map { key ->
                    "${key.tojstring()}=${table.get(key).tojstring()}"
                }
                runBlocking { cookieStore.setFromResponse(url.checkjstring(), headers) }
                return LuaValue.NIL
            }
        })

        globals.set("get_localStorage", object : TwoArgFunction() {
            override fun call(hostArg: LuaValue, key: LuaValue): LuaValue {
                val host = hostOf(hostArg.checkjstring())
                val value = keyValueStore.get(host, key.checkjstring()) ?: return LuaValue.NIL
                return LuaValue.valueOf(value)
            }
        })
        globals.set("set_localStorage", object : ThreeArgFunction() {
            override fun call(hostArg: LuaValue, key: LuaValue, value: LuaValue): LuaValue {
                keyValueStore.set(hostOf(hostArg.checkjstring()), key.checkjstring(), value.checkjstring())
                return LuaValue.NIL
            }
        })

        globals.set("get_preference", object : OneArgFunction() {
            override fun call(key: LuaValue): LuaValue {
                val value = keyValueStore.get(PREFS_NAMESPACE, key.checkjstring()) ?: return LuaValue.NIL
                return LuaValue.valueOf(value)
            }
        })
        globals.set("set_preference", object : TwoArgFunction() {
            override fun call(key: LuaValue, value: LuaValue): LuaValue {
                keyValueStore.set(PREFS_NAMESPACE, key.checkjstring(), value.checkjstring())
                return LuaValue.NIL
            }
        })

        // show_error: park a UserMessage on the session; LuaSession.call
        // bridges it into LuaCallResult.error for THIS call (no ThreadLocal).
        // Optional 3rd arg = authUrl (NoveLA); absent -> nil, so the 2-arg
        // form stays byte-compatible.
        // ceiling: the message is read once per call(); a script calling
        // show_error twice in one call keeps only the last one.
        globals.set("show_error", object : ThreeArgFunction() {
            override fun call(title: LuaValue, message: LuaValue, authUrl: LuaValue): LuaValue {
                session.pendingUserError = PluginError.UserMessage(
                    title.checkjstring(),
                    message.checkjstring(),
                    authUrl.optjstring(null),
                )
                return LuaValue.NIL
            }
        })
    }

    // NoveLA LuaSourceLoader.kt:956-970: the first arg is a URL whose host
    // names the entry; a bare host passes through unchanged.
    private fun hostOf(urlOrHost: String): String = try {
        java.net.URI(urlOrHost).host ?: urlOrHost
    } catch (_: Exception) {
        urlOrHost
    }

    private companion object {
        // NoveLA's SharedPreferences file name for plugin preferences.
        const val PREFS_NAMESPACE = "lua_preferences"
    }
}
