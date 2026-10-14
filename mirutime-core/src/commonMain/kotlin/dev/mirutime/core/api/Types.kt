package dev.mirutime.core.api

import dev.mirutime.core.network.cf.CfOptions

enum class ContentType { NOVEL, MANGA, VIDEO }

// Plugin API level this library implements. A plugin declares the API it was
// written against in metadata.api (and index.yaml `api`); anything else is
// incompatible and surfaces as PluginError.UnsupportedApi — the ethalon
// NoveLA has no such gate, this is the spec'd MiruTime contract.
const val SUPPORTED_PLUGIN_API: Int = 1

data class PluginMetadata(
    val id: String,
    val name: String,
    val baseUrl: String,
    val contentType: ContentType,
    val language: String,
    val api: Int,
    val version: String,
    val sha256: String,
    // Optional per-plugin User-Agent override; null falls back to SystemInfo.defaultUserAgent.
    val userAgentPreset: String? = null,
    // Optional per-plugin CF detection config; null means CfOptions defaults.
    val cfOptions: CfOptions? = null,
    // Response charset declared by the plugin's `charset` global (NoveLA:
    // SourceInterface.charset, LuaSourceAdapter.kt:94 `metadata.charset ?:
    // "UTF-8"`). Null = undeclared; response decoding falls back to the
    // Content-Type header, then UTF-8 (see CharsetResolver).
    val charset: String? = null,
    // Display metadata from the script header (`icon`/`description` globals,
    // NoveLA LuaSourceAdapter.extractMetadata). Raw strings, exactly as
    // declared — the host resolves a relative icon against baseUrl the way
    // NoveLA's iconUrl does. Null = undeclared.
    val icon: String? = null,
    val description: String? = null,
)

data class CatalogItem(
    val title: String,
    val url: String,
    val coverUrl: String? = null,
    val rating: String? = null,
    val author: String? = null,
)

data class CatalogPage(
    val items: List<CatalogItem>,
    val hasNext: Boolean,
)

data class BookMeta(
    val title: String,
    val coverUrl: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val rating: String? = null,
    val status: String? = null,
    val lastUpdated: String? = null,
    val author: String? = null,
    // Arbitrary plugin-declared metadata (tags, alt titles, chapter count,
    // translator, ...): plugins choose what to put here, the future DB stores
    // one JSON blob of extras instead of a column per field. Canonical fields
    // above are never stored here — extras never override them.
    val extras: Map<String, String> = emptyMap(),
)

data class Chapter(
    val title: String,
    val url: String,
    val volume: String? = null,
    // Update timestamp as epoch millis. NoveLA precedent
    // (LuaSourceAdapter.kt:648): `uploaded =
    // table.get("uploaded").takeIf { it.isnumber() }?.tolong()` — only a Lua
    // NUMBER converts; date strings and anything else yield null.
    val uploaded: Long? = null,
)

sealed class ChapterContent {
    data class Text(val text: String) : ChapterContent()
    data class Pages(val pageUrls: List<String>) : ChapterContent()
    data class Video(val streams: List<VideoStream>) : ChapterContent()
}

data class VideoStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mime: String? = null,
    val quality: String? = null,
    val subtitles: List<SubtitleTrack> = emptyList(),
)

data class SubtitleTrack(
    val url: String,
    val language: String? = null,
    val label: String? = null,
)
