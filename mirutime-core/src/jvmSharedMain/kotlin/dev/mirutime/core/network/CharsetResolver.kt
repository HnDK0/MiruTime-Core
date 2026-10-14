package dev.mirutime.core.network

// Resolve a plugin's response charset from the available evidence,
// in priority order (controller ruling):
//   1. plugin metadata (metaCharset from PluginMetadata)
//   2. charset= parameter in the Content-Type response header
//   3. <meta charset=...> (or http-equiv Content-Type) in the first ~1KB
//   4. UTF-8 fallback
fun pluginCharset(metaCharset: String?, contentType: String?, htmlHead: String?): String {
    metaCharset?.takeIf { it.isNotBlank() }?.let { return it }

    contentType?.let { header ->
        Regex("""charset\s*=\s*"?([^";\s]+)""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.let { return it }
    }

    htmlHead?.take(1024)?.let { head ->
        Regex("""<meta[^>]+charset\s*=\s*"?([^"'\s>]+)""", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1)?.let { return it }
    }

    return "UTF-8"
}
