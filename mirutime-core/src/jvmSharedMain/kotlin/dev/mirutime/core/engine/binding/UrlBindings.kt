package dev.mirutime.core.engine.binding

import dev.mirutime.core.engine.LuaSession
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import java.net.URI
import java.net.URLEncoder

// URL bindings: encoding (NoveLA UrlEncodeFunction) and resolution
// (NoveLA UrlResolveFunction precedent: unparseable base falls back to the
// raw relative part). Encoding failures and unresolvable input -> nil/rel.
object UrlBindings {

    fun install(session: LuaSession) {
        session.globals.set("url_encode", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = try {
                LuaValue.valueOf(URLEncoder.encode(arg.checkjstring(), "UTF-8"))
            } catch (e: Exception) {
                LuaValue.NIL
            }
        })
        // url_encode_charset(str, charset) — GBK search paths (Shuba69/PiaoTia).
        session.globals.set("url_encode_charset", object : TwoArgFunction() {
            override fun call(str: LuaValue, charset: LuaValue): LuaValue = try {
                LuaValue.valueOf(URLEncoder.encode(str.checkjstring(), charset.optjstring("UTF-8")))
            } catch (e: Exception) {
                LuaValue.NIL
            }
        })
        session.globals.set("url_resolve", object : TwoArgFunction() {
            override fun call(base: LuaValue, rel: LuaValue): LuaValue {
                val baseStr = base.checkjstring()
                val relStr = rel.checkjstring()
                val resolved = try {
                    URI(baseStr).resolve(relStr).toString()
                } catch (e: Exception) {
                    relStr
                }
                return LuaValue.valueOf(resolved)
            }
        })
    }
}
