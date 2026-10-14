package dev.mirutime.core.installer

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.engine.createSandboxGlobals
import dev.mirutime.core.network.cf.CfOptions
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction

// cf_options declared by a plugin script (external-sources plugin guide):
// whitelist / ignore_markers / trigger_markers. The Lua field names are kept
// verbatim — do not rename.
data class ScriptCfOptions(
    val whitelist: Boolean,
    val ignoreMarkers: List<String>,
    val triggerMarkers: List<String>,
)

// Plugin metadata read from a script's header globals. Mirrors NoveLA's
// LuaSourceAdapter.extractMetadata (id/name/version/baseUrl/language/
// content_type/icon/description) plus cf_options. Null means the global was
// not declared; the caller supplies the index entry as fallback.
data class ScriptMetadata(
    val id: String?,
    val name: String?,
    val version: String?,
    val baseUrl: String?,
    val language: String?,
    val contentType: String?,
    val icon: String?,
    val description: String?,
    val cfOptions: ScriptCfOptions?,
    // NoveLA LuaSourceAdapter.kt:211: `charset =
    // s("charset").takeIf { it.isNotBlank() }`.
    val charset: String?,
    // NoveLA LuaSourceAdapter.kt:193-196 (readReferer): the `referer` global
    // trimmed, "" when undeclared. Only a string global counts (a boolean or
    // table value is not a Referer — NoveLA's referer test pins "", see
    // LuaSourceAdapterRefererTest).
    val referer: String?,
)

// Script-declared cf_options projected onto the detector config (NoveLA:
// LuaSourceAdapter.registerCfOptions reads whitelist/ignore_markers/
// trigger_markers and registers a CfDomainOptions per baseUrl host).
fun ScriptCfOptions.toCfOptions(): CfOptions =
    CfOptions(
        whitelist = whitelist,
        ignoreMarkers = ignoreMarkers,
        triggerMarkers = triggerMarkers,
    )

// Projects header metadata onto the engine's PluginMetadata, filling the
// fields an index entry owns (id fallback, api, sha256). icon/description,
// charset and the cf_options marker sets pass through verbatim.
fun ScriptMetadata.toPluginMetadata(fallbackId: String, api: Int, sha256: String): PluginMetadata =
    PluginMetadata(
        id = id ?: fallbackId,
        name = name ?: fallbackId,
        baseUrl = baseUrl.orEmpty(),
        contentType = when (contentType?.lowercase()) {
            "manga" -> ContentType.MANGA
            "video" -> ContentType.VIDEO
            else -> ContentType.NOVEL
        },
        language = language ?: "en",
        api = api,
        version = version ?: "1.0.0",
        sha256 = sha256,
        cfOptions = cfOptions?.toCfOptions(),
        charset = charset,
        icon = icon,
        description = description,
    )

// Executes a plugin script's top-level header once in the sandbox and reads
// the metadata globals back. Mirrors NoveLA's extractMetadata, which reads
// globals from an already-run script.
//
// require_lib is stubbed to an empty table: a top-level
// `local x = require_lib("...")` must not abort the header before the metadata
// globals are assigned (real libs load later, per session, in LibraryLoader).
// A throw partway through the header is swallowed — the globals assigned
// before it are already visible.
// Load-time introspection for tooling (plugin-tester): which entry points from
// `candidates` a script defines as globals. Runs the header once in the SAME
// sandbox ScriptMetadataParser uses — require_lib stubbed, load errors
// swallowed — and checks each candidate for a function value. NO plugin
// function is ever invoked, so the check makes no network call.
// Exists because LuaSession.globals is internal to this module: a host that
// wants the "functions defined" list has no other read-only door onto it.
// ceiling: a script that throws in its top level reports only the candidates
// defined before the throw.
fun definedFunctions(code: String, candidates: Collection<String>): Set<String> {
    val globals = createSandboxGlobals()
    globals.set("require_lib", object : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = LuaTable()
    })
    try {
        globals.load(code, "@introspect").call()
    } catch (_: Exception) {
        // Functions defined before the failing statement are still readable.
    }
    return candidates.filterTo(mutableSetOf()) { name ->
        try {
            globals.get(name).isfunction()
        } catch (_: Exception) {
            false
        }
    }
}

object ScriptMetadataParser {
    fun parse(code: String): ScriptMetadata {
        val globals = createSandboxGlobals()
        globals.set("require_lib", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = LuaTable()
        })
        try {
            globals.load(code, "@metadata").call()
        } catch (_: Exception) {
            // Metadata globals set before the failing statement are still readable.
        }
        return parseGlobals(globals)
    }

    // Reads the metadata globals back from an ALREADY-RUN Globals — the
    // engine lane calls this on the live session after the plugin chunk ran,
    // so load-time registration (referer/cf_options) sees exactly what the
    // running plugin declared, without re-executing the header.
    fun parseGlobals(globals: Globals): ScriptMetadata =
        ScriptMetadata(
            id = globals.str("id"),
            name = globals.str("name"),
            version = globals.str("version"),
            baseUrl = globals.str("baseUrl"),
            language = globals.normalizedLanguage(),
            contentType = globals.contentType(),
            icon = globals.str("icon"),
            description = globals.str("description"),
            cfOptions = globals.cfOptions(),
            charset = globals.str("charset"),
            referer = globals.referer(),
        )

    private fun Globals.str(key: String): String? =
        try {
            get(key).optjstring(null)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

    // NoveLA LuaSourceAdapter.kt:193-196: `val raw = try {
    // luaScript.get("referer").optjstring("") } catch (_: Exception) { "" };
    // return raw.trim()`. Only a string global is a Referer — anything else
    // (boolean/table, see NoveLA LuaSourceAdapterRefererTest) reads as "".
    private fun Globals.referer(): String? =
        try {
            get("referer").takeIf { it.isstring() }?.tojstring()?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

    // NoveLA LuaSourceAdapter.kt:101-105: `override val language:
    // LanguageCode? = when (metadata.language.lowercase().trim()) { "mtl",
    // "multi" -> LanguageCode.MTL; else ->
    // LanguageCode.values().find { it.iso639_1.equals(metadata.language,
    // ignoreCase = true) } ?: LanguageCode.ENGLISH }`. This library has no
    // LanguageCode enum — the String form keeps the same normalization
    // (lowercase/trim, mtl+multi folded to "mtl") and unknown codes verbatim
    // (no silent "en": the corpus carries only valid iso639_1 codes plus mtl).
    private fun Globals.normalizedLanguage(): String? {
        val raw = str("language") ?: return null
        val trimmed = raw.trim()
        if (trimmed.equals("mtl", ignoreCase = true) || trimmed.equals("multi", ignoreCase = true)) return "mtl"
        return trimmed.lowercase()
    }

    // Same validation boundary as NoveLA readContentType: only manga/novel/video.
    private fun Globals.contentType(): String? =
        str("content_type")?.lowercase()?.takeIf { it == "manga" || it == "novel" || it == "video" }

    private fun Globals.cfOptions(): ScriptCfOptions? {
        val raw = try { get("cf_options") } catch (_: Exception) { return null }
        if (!raw.istable()) return null
        val table = raw.checktable()
        return ScriptCfOptions(
            whitelist = table.get("whitelist").optboolean(false),
            ignoreMarkers = table.strList("ignore_markers"),
            triggerMarkers = table.strList("trigger_markers"),
        )
    }

    private fun LuaTable.strList(key: String): List<String> {
        val array = get(key).opttable(null) ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 1..array.length()) {
            array.get(i).optjstring(null)?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out
    }
}
