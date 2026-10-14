package dev.mirutime.core.network

// Browser User-Agent presets, ported verbatim from NoveLA
// networking/.../interceptors/UserAgentPresets.kt (UAPresets). A plugin selects
// a preset by name (Lua global getUserAgentPreset) or by its alias; the engine
// registers the NAME per source-id/baseUrl-host in PluginUserAgentRegistry and
// the effective UA is resolved here. A request must never carry a bare preset
// name or a non-browser UA, so DEFAULT is a full Chromium UA.
object UserAgentPresets {
    const val CHROME_150_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
    const val SAFARI_18_MACOS = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"
    const val FIREFOX_152_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:152.0) Gecko/20100101 Firefox/152.0"
    const val EDGE_150_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36 Edg/150.0.0.0"
    const val CHROME_150_ANDROID = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
    const val SAFARI_18_IOS = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
    const val FIREFOX_152_ANDROID = "Mozilla/5.0 (Android 16; Mobile; rv:152.0) Gecko/152.0 Firefox/152.0"
    const val EDGE_150_ANDROID = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36 EdgA/150.0.0.0"
    const val SAMSUNG_30_ANDROID = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/30.0 Chrome/143.0.0.0 Mobile Safari/537.36"

    // NoveLA GLOBAL_USER_AGENT: the app-wide browser default used when no preset
    // is configured. A full Chromium UA so a request is never sent bare.
    const val DEFAULT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"

    private val all = mapOf(
        "Chrome 150 (Windows)" to CHROME_150_WINDOWS,
        "Safari 18 (macOS)" to SAFARI_18_MACOS,
        "Firefox 152 (Windows)" to FIREFOX_152_WINDOWS,
        "Edge 150 (Windows)" to EDGE_150_WINDOWS,
        "Chrome 150 (Android)" to CHROME_150_ANDROID,
        "Safari 18 (iOS)" to SAFARI_18_IOS,
        "Firefox 152 (Android)" to FIREFOX_152_ANDROID,
        "Edge 150 (Android)" to EDGE_150_ANDROID,
        "Samsung Internet 30.0 (Android)" to SAMSUNG_30_ANDROID,
    )

    private val aliases = mapOf(
        "Chrome Desktop" to CHROME_150_WINDOWS,
        "Safari Desktop" to SAFARI_18_MACOS,
        "Firefox Desktop" to FIREFOX_152_WINDOWS,
        "Edge Desktop" to EDGE_150_WINDOWS,
        "Chrome Mobile" to CHROME_150_ANDROID,
        "Safari Mobile" to SAFARI_18_IOS,
        "Firefox Mobile" to FIREFOX_152_ANDROID,
        "Edge Mobile" to EDGE_150_ANDROID,
        "Samsung Mobile" to SAMSUNG_30_ANDROID,
    )

    fun resolve(presetName: String): String? = all[presetName] ?: aliases[presetName]

    fun allPresets(): Map<String, String> = all
}

// NoveLA precedent: PluginUARegistry. A plugin's getUserAgentPreset() returns a
// preset NAME; the engine (separate lane) registers it here keyed by source-id
// and baseUrl host. Lookups resolve name -> UA and never return a bare name:
// resolve() falls back to UserAgentPresets.DEFAULT.
// ceiling: plain maps, last-writer-wins, registration is expected to be
// serialized (plugin-management path, not the per-request hot path) — the same
// ceiling as HostOptionsRegistry.
object PluginUserAgentRegistry {
    private val sourcePresets = mutableMapOf<String, String>()
    private val hostPresets = mutableMapOf<String, String>()

    fun register(sourceId: String, presetName: String) {
        sourcePresets[sourceId] = presetName
    }

    fun getPreset(sourceId: String): String? = sourcePresets[sourceId]

    fun registerHost(host: String, presetName: String) {
        hostPresets[normalizeHost(host)] = presetName
    }

    // Exact host first, then each parent domain ("a.b.example" -> "b.example") —
    // a preset registered for a registrable domain covers its subdomains.
    fun getPresetForHost(requestHost: String): String? {
        val host = normalizeHost(requestHost)
        hostPresets[host]?.let { return it }
        val parts = host.split('.')
        for (i in 1 until parts.size - 1) {
            hostPresets[parts.subList(i, parts.size).joinToString(".")]?.let { return it }
        }
        return null
    }

    fun resolveUAString(presetName: String): String? = UserAgentPresets.resolve(presetName)

    // Effective UA: source-id preset, then host preset, then a literal configured
    // value, else the browser default. Never blank, never a bare preset name.
    fun resolve(sourceId: String?, host: String?): String {
        val configured = sourceId?.let { getPreset(it) }
            ?: host?.let { getPresetForHost(it) }
        return UserAgentPresets.resolve(configured ?: "")
            ?: configured?.takeIf { it.isNotBlank() }
            ?: UserAgentPresets.DEFAULT
    }

    fun clear() {
        sourcePresets.clear()
        hostPresets.clear()
    }
}
