package dev.mirutime.core.engine.binding

import com.fleeksoft.ksoup.Ksoup
import dev.mirutime.core.engine.LuaSession
import dev.mirutime.core.network.PluginUserAgentRegistry
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.ZeroArgFunction

// Globals that belong to no single category: pagination detection, the
// effective User-Agent, and the string form of base64_decode.
object MiscBindings {

    fun install(session: LuaSession) {
        val globals = session.globals

        // detect_pagination(html_or_element [, unused]) -> { hasNext, next_url }
        // NoveLA keeps the second argument unused (reserved); so do we.
        // On any failure -> nil. next_url falls back to the raw href because
        // Ksoup's abs:href is empty without a base URI (NoveLA's Jsoup returns
        // the relative part in that case).
        globals.set("detect_pagination", object : TwoArgFunction() {
            override fun call(html: LuaValue, unused: LuaValue): LuaValue = try {
                val doc = Ksoup.parse(htmlFromValue(html), "")
                val next = doc.select("a[href]:contains(next), a[href]:contains(›), a[href]:contains(»)")
                LuaTable().also { t ->
                    t.set("hasNext", LuaValue.valueOf(next.isNotEmpty()))
                    val nextUrl = next.firstOrNull()?.let {
                        it.attr("abs:href").ifEmpty { it.attr("href") }
                    }
                    t.set("next_url", if (!nextUrl.isNullOrBlank()) LuaValue.valueOf(nextUrl) else LuaValue.NIL)
                }
            } catch (e: Exception) {
                LuaValue.NIL
            }
        })

        // get_user_agent() — the UA http_get/http_post send for this source
        // (NoveLA webviewUserAgent). resolve(sourceId, null) applies the
        // plugin's preset, else the app-wide browser default.
        globals.set("get_user_agent", object : ZeroArgFunction() {
            override fun call(): LuaValue =
                LuaValue.valueOf(PluginUserAgentRegistry.resolve(session.sourceId, null))
        })

        // base64_decode(str) — string form (NoveLA Base64DecodeFunction):
        // decode to UTF-8 text; any failure -> nil. Delegates to the lenient
        // base64_decode_bytes installed by CryptoBindings so both stay in sync.
        globals.set("base64_decode", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = try {
                val decoded = globals.get("base64_decode_bytes").call(arg)
                if (decoded.isnil()) {
                    LuaValue.NIL
                } else {
                    val s = decoded.checkstring()
                    LuaValue.valueOf(String(s.m_bytes, s.m_offset, s.m_length, Charsets.UTF_8))
                }
            } catch (e: Exception) {
                LuaValue.NIL
            }
        })
    }

    private fun htmlFromValue(v: LuaValue): String =
        if (v.istable()) v.checktable().get("html").optjstring("") else v.checkjstring()
}
