package dev.mirutime.core.network

import dev.mirutime.core.host.SystemInfo
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url

// NoveLA ChromeHeadersInterceptor parity: every plugin request carries a full
// Chrome header set (sec-ch-ua with GREASE, sec-fetch-*, upgrade-insecure-requests,
// priority, Accept-Encoding), a per-kind Accept and a default Referer =
// scheme://host/. Caller-supplied headers (extraHeaders) always win and are
// applied last.
// The Chromium-only set (sec-ch-ua, priority) is sent only when the UA is
// Chromium-based ("Chrome/NNN"): NoveLA leaves Firefox/Safari untouched because
// the TLS/h2 stack is Chrome-shaped and a non-Chrome UA cannot be made consistent.
class PluginRequestBuilder(
    private val systemInfo: SystemInfo,
    private val userAgentPreset: String?,
) {
    // userAgentOverride: the EFFECTIVE UA for this request (a per-host override
    // or a CF solver's browser UA). sec-ch-ua-* is derived from it, so the
    // client-hints family always matches the UA actually sent (NoveLA
    // ChromeHeadersInterceptor derives hints from the final UA, never from the
    // preset).
    // refererOverride: the plugin's per-host `referer` global (NoveLA
    // SourceInterface.referer, for hotlink-protected covers). Applies only
    // when the caller sent no explicit Referer — like every other default,
    // the caller's value wins.
    fun applyTo(
        builder: HttpRequestBuilder,
        extraHeaders: Map<String, String>,
        userAgentOverride: String? = null,
        refererOverride: String? = null,
    ) {
        val host = builder.url.host
        // Effective UA precedence (NoveLA: hints always follow the final UA):
        // caller header > per-request override (solver/per-host) > preset.
        val ua = extraHeaders.valueIgnoreCase(HttpHeaders.UserAgent)
            ?: userAgentOverride
            ?: resolveUserAgent(host)
        builder.headers.set(HttpHeaders.UserAgent, ua)
        builder.headers.set(HttpHeaders.AcceptLanguage, systemInfo.acceptLanguage)

        val callerAccept = extraHeaders.valueIgnoreCase("Accept")
        val callerOrigin = extraHeaders.valueIgnoreCase("Origin")
        val callerReferer = extraHeaders.valueIgnoreCase("Referer")
        // Default Referer = scheme://host/ (NoveLA defaultHeaders): enough for most
        // anti-scraping checks without leaking the path.
        val referer = callerReferer ?: refererOverride?.takeIf { it.isNotBlank() } ?: defaultReferer(builder.url)
        val kind = kindFor(callerAccept, callerOrigin, extraHeaders.valueIgnoreCase("X-Requested-With"))

        val major = CHROME_MAJOR.find(ua)?.groupValues?.get(1)?.toIntOrNull()
        if (major != null) {
            builder.headers.set("sec-ch-ua", secChUa(major, isEdge(ua)))
            builder.headers.set("sec-ch-ua-mobile", if (ua.contains("Mobile")) "?1" else "?0")
            builder.headers.set("sec-ch-ua-platform", "\"${platformOf(ua)}\"")
            builder.headers.set("sec-fetch-dest", destOf(kind))
            builder.headers.set("sec-fetch-mode", modeOf(kind))
            builder.headers.set("sec-fetch-site", siteOf(kind, host, callerOrigin, referer))
            if (kind == Kind.NAVIGATE) {
                builder.headers.set("upgrade-insecure-requests", "1")
                // Confirmed against a real WebView capture: navigations send "priority: u=0, i".
                builder.headers.set("priority", "u=0, i")
                // NoveLA sends sec-fetch-user only when a navigation has no Referer.
                if (referer == null) builder.headers.set("sec-fetch-user", "?1")
            }
        }
        builder.headers.set(HttpHeaders.Accept, acceptOf(kind))
        // Built from the encoder list PluginHttpClient registers in
        // ContentEncoding — one source of truth, so advertising an encoding we
        // cannot decode (or failing to advertise one we can) is impossible.
        builder.headers.set(HttpHeaders.AcceptEncoding, ACCEPT_ENCODING)
        referer?.let { builder.headers.set("Referer", it) }

        // Caller headers win (NoveLA: caller-supplied value beats our default).
        extraHeaders.forEach { (name, value) -> builder.headers.set(name, value) }
    }

    // A preset NAME resolves to its full UA; an unknown value is used literally
    // (a plugin may hardcode a full UA). With no configured preset the host
    // registry is consulted, and only then the browser default — never
    // SystemInfo.defaultUserAgent, so a request never carries a non-browser UA
    // (NoveLA always sends a browser UA). See PluginUserAgentRegistry.resolve.
    // internal: PluginHttpClient folds the EFFECTIVE per-host UA into its cache
    // key, so a per-host override can never serve a body cached for another UA.
    internal fun resolveUserAgent(host: String): String {
        val configured = userAgentPreset
        if (configured != null) {
            UserAgentPresets.resolve(configured)?.let { return it }
            if (configured.isNotBlank()) return configured
        }
        return PluginUserAgentRegistry.resolve(null, host)
    }

    private enum class Kind { NAVIGATE, FETCH, IMAGE }

    private fun kindFor(accept: String?, origin: String?, xhr: String?): Kind = when {
        accept?.startsWith("image/") == true -> Kind.IMAGE
        origin != null || xhr != null || accept?.contains("json", ignoreCase = true) == true -> Kind.FETCH
        else -> Kind.NAVIGATE
    }

    private fun acceptOf(kind: Kind): String = when (kind) {
        Kind.NAVIGATE -> NAV_ACCEPT
        Kind.IMAGE -> IMG_ACCEPT
        Kind.FETCH -> "*/*"
    }

    private fun destOf(kind: Kind): String = when (kind) {
        Kind.NAVIGATE -> "document"
        Kind.FETCH -> "empty"
        Kind.IMAGE -> "image"
    }

    private fun modeOf(kind: Kind): String = when (kind) {
        Kind.NAVIGATE -> "navigate"
        Kind.FETCH -> "cors"
        Kind.IMAGE -> "no-cors"
    }

    // NoveLA ChromeHeadersInterceptor.site: same-origin / same-site (crude eTLD+1)
    // / cross-site against the request host.
    private fun siteOf(kind: Kind, host: String, origin: String?, referer: String?): String {
        val other = when (kind) {
            Kind.NAVIGATE -> referer
            Kind.FETCH -> origin ?: referer
            Kind.IMAGE -> referer
        }
        if (other == null) return if (kind == Kind.NAVIGATE) "none" else "same-origin"
        val h = other.substringAfter("://").substringBefore('/').substringBefore(':')
        return when {
            h.equals(host, ignoreCase = true) -> "same-origin"
            registrable(h) == registrable(host) -> "same-site"
            else -> "cross-site"
        }
    }

    private fun registrable(h: String): String = h.split('.').takeLast(2).joinToString(".")

    companion object {
        // NoveLA ChromeHeadersInterceptor: default per-kind Accept values.
        // "image/avif" is intentionally omitted so the CDN answers webp/jpeg,
        // which decode everywhere.
        const val NAV_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp," +
                "image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
        const val IMG_ACCEPT = "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"

        private val CHROME_MAJOR = Regex("""Chrome/(\d+)""")
        private val GREASE_CHARS = arrayOf(" ", "(", ":", "-", ".", "/", ")", ";", "=", "?", "_")
        private val GREASE_VERSIONS = arrayOf("8", "99", "24")
        private val ORDERS = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2),
            intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0),
        )

        // Same algorithm as Chromium's GenerateBrandVersionList (seed = major version).
        fun secChUa(major: Int, edge: Boolean): String {
            val grease = "Not${GREASE_CHARS[major % 11]}A${GREASE_CHARS[(major + 1) % 11]}Brand"
            val list = Array(3) { "" }
            val order = ORDERS[major % 6]
            list[order[0]] = "\"$grease\";v=\"${GREASE_VERSIONS[major % 3]}\""
            list[order[1]] = "\"Chromium\";v=\"$major\""
            list[order[2]] = "\"${if (edge) "Microsoft Edge" else "Google Chrome"}\";v=\"$major\""
            return list.joinToString(", ")
        }

        private fun isEdge(ua: String): Boolean = ua.contains("Edg/") || ua.contains("EdgA/")

        private fun platformOf(ua: String): String = when {
            ua.contains("Android") -> "Android"
            ua.contains("Windows") -> "Windows"
            ua.contains("Macintosh") -> "macOS"
            ua.contains("Linux") -> "Linux"
            else -> "Windows"
        }
    }
}

// NoveLA LuaSourceLoader.refererFromUrl: minimal Referer (scheme://host/) that
// does not leak the path but satisfies most anti-scraping checks. Falls back to
// the input on an unparseable URL. internal: PluginRequestBuilder computes the
// same default itself; this is the reusable helper for host-side callers.
internal fun refererFromUrl(url: String): String = try {
    val parsed = Url(url)
    if (parsed.host.isEmpty()) url else "${parsed.protocol.name}://${parsed.host}/"
} catch (_: Exception) {
    url
}

// null when the URL carries no host (origin-form/relative), so no Referer is sent.
private fun defaultReferer(url: URLBuilder): String? =
    url.host.takeIf { it.isNotEmpty() }?.let { "${url.protocol.name}://$it/" }

// Caller headers are matched case-insensitively (Lua plugins are inconsistent
// about header casing).
private fun Map<String, String>.valueIgnoreCase(key: String): String? =
    entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value