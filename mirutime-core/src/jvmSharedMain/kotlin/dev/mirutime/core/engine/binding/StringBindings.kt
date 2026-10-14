package dev.mirutime.core.engine.binding

import dev.mirutime.core.engine.LuaSession
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import java.text.Normalizer

// Regex + string-util bindings, ported from NoveLA LuaSourceLoader.
// regex_match keeps NoveLA's exact match-table semantics: the earlier boolean
// ruling (containsMatchIn) was reverted per user decision — 11/96 corpus
// plugins index the result as an array (m[1]), none use it as a boolean.
// The string_* helpers keep NoveLA's exact fallbacks (return the input /
// false / an empty table).
object StringBindings {

    fun install(session: LuaSession) {
        val globals = session.globals
        // regex_match(str, pattern) -> 1-based array of full-match strings
        // (m.value, not capture groups); no match or error -> empty table.
        globals.set("regex_match", object : TwoArgFunction() {
            override fun call(str: LuaValue, pattern: LuaValue): LuaValue = try {
                LuaTable().also { t ->
                    Regex(pattern.checkjstring()).findAll(str.checkjstring())
                        .forEachIndexed { i, m -> t.set(i + 1, LuaValue.valueOf(m.value)) }
                }
            } catch (e: Exception) {
                LuaTable()
            }
        })
        globals.set("regex_replace", object : ThreeArgFunction() {
            override fun call(str: LuaValue, pattern: LuaValue, replacement: LuaValue): LuaValue = try {
                LuaValue.valueOf(
                    Regex(pattern.checkjstring()).replace(str.checkjstring(), replacement.checkjstring())
                )
            } catch (e: Exception) {
                str
            }
        })

        // string_normalize(str) -> NFKC; on error returns the input.
        globals.set("string_normalize", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = try {
                LuaValue.valueOf(Normalizer.normalize(arg.checkjstring(), Normalizer.Form.NFKC))
            } catch (e: Exception) {
                arg
            }
        })
        // string_split(str, sep) -> 1-based array; on error an empty table.
        globals.set("string_split", object : TwoArgFunction() {
            override fun call(str: LuaValue, sep: LuaValue): LuaValue = try {
                val parts = str.checkjstring().split(sep.checkjstring())
                LuaTable().also { t -> parts.forEachIndexed { i, s -> t.set(i + 1, LuaValue.valueOf(s)) } }
            } catch (e: Exception) {
                LuaTable()
            }
        })
        // string_trim(str) -> trimmed; on error the input.
        globals.set("string_trim", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = try {
                LuaValue.valueOf(arg.checkjstring().trim())
            } catch (e: Exception) {
                arg
            }
        })
        globals.set("string_starts_with", object : TwoArgFunction() {
            override fun call(str: LuaValue, prefix: LuaValue): LuaValue = try {
                LuaValue.valueOf(str.checkjstring().startsWith(prefix.checkjstring()))
            } catch (e: Exception) {
                LuaValue.FALSE
            }
        })
        globals.set("string_ends_with", object : TwoArgFunction() {
            override fun call(str: LuaValue, suffix: LuaValue): LuaValue = try {
                LuaValue.valueOf(str.checkjstring().endsWith(suffix.checkjstring()))
            } catch (e: Exception) {
                LuaValue.FALSE
            }
        })
        // string_clean(str) = NFKC + collapse runs of whitespace + trim.
        globals.set("string_clean", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val s = arg.optjstring("")
                val normalized = Normalizer.normalize(s, Normalizer.Form.NFKC)
                return LuaValue.valueOf(normalized.replace(Regex("""\s+"""), " ").trim())
            }
        })
        // unescape_unicode(str): \uXXXX -> char; on error the input.
        globals.set("unescape_unicode", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = try {
                LuaValue.valueOf(
                    Regex("\\\\u([0-9a-fA-F]{4})").replace(arg.checkjstring()) { m ->
                        m.groupValues[1].toInt(16).toChar().toString()
                    }
                )
            } catch (e: Exception) {
                arg
            }
        })
    }
}
