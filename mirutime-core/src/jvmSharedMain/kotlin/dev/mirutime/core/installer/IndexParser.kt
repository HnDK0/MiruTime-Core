package dev.mirutime.core.installer

import org.yaml.snakeyaml.Yaml

// One catalog entry of an index.yaml file (external-sources layout): the
// language files carry `sources:`, the root file carries `libraries:` —
// both are lists of {id, url, sha256, ...}. sha256 is optional in the file
// (older language indexes predate it); the installer refuses entries
// without it.
data class IndexEntry(
    val id: String,
    val name: String?,
    val version: String?,
    val url: String,
    val sha256: String?,
    // Plugin API level the entry was published against; null when the index
    // predates the field (the real language indexes currently omit it) — the
    // installer treats null as "no constraint", the registry gate still
    // enforces SUPPORTED_PLUGIN_API at load time via metadata.api.
    val api: Int? = null,
)

data class PluginIndex(
    val sources: List<IndexEntry>,
    val libraries: List<IndexEntry>,
)

class IndexParseException(message: String) : Exception(message)

// Parses an index.yaml document via snakeyaml (installer-only dependency —
// its safe constructor blocks global tags by default in 2.x). ceiling:
// entries lacking id/url are skipped, not rejected — a single malformed
// catalog line must not kill the whole index.
fun parsePluginIndex(yamlText: String): PluginIndex {
    val root = Yaml().load<Any>(yamlText)
        ?: return PluginIndex(emptyList(), emptyList())
    val map = root as? Map<*, *>
        ?: throw IndexParseException("index root must be a mapping")
    return PluginIndex(
        sources = parseEntries(map["sources"]),
        libraries = parseEntries(map["libraries"]),
    )
}

private fun parseEntries(section: Any?): List<IndexEntry> {
    if (section == null) return emptyList()
    val list = section as? List<*>
        ?: throw IndexParseException("index section must be a list")
    return list.mapNotNull { raw ->
        val map = raw as? Map<*, *> ?: return@mapNotNull null
        val id = map["id"] as? String ?: return@mapNotNull null
        val url = map["url"] as? String ?: return@mapNotNull null
        IndexEntry(
            id = id,
            name = map["name"] as? String,
            version = map["version"] as? String,
            url = url,
            sha256 = map["sha256"] as? String,
            api = when (val raw = map["api"]) {
                is Number -> raw.toInt()
                is String -> raw.toIntOrNull()
                else -> null
            },
        )
    }
}
