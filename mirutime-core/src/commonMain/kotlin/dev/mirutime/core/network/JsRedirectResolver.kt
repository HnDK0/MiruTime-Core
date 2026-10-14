package dev.mirutime.core.network

// Minimal port of NoveLA's JsRedirectResolver
// (networking/.../my/noveldokusha/network/JsRedirectResolver.kt): extracts a
// JS/meta navigation target from a raw HTML page. Some landing sites (e.g.
// readnovel.site) answer with a "Loading..." page plus a JS redirect to the
// real site; the HTTP client never runs JS, so the target is extracted by
// hand. String-based (commonMain has no HTML parser): meta refresh is matched
// on the raw tag, script navigations on the whole body.
//
// Wired into fetchFollowingRedirects for HTML GET responses (RedirectFollower):
// the hop is SSRF-validated like every other hop, carries Referer = the page
// it came from (NoveLA's DownloaderRepository sets Referer: realUrl on the
// manual JS hop), and counts against MAX_REDIRECTS.
// ceiling: pattern-matched, so an inline `location = "..."` in an ad/analytics
// snippet can false-positive — NoveLA scopes the call to chapter fetch and
// validates the target's content, which a byte-level redirect follower cannot.
object JsRedirectResolver {

    // NoveLA: `Regex("""url\s*=\s*['"]?([^'">\s]+)""", IGNORE_CASE)`.
    private val META_REFRESH_URL = Regex("""url\s*=\s*['"]?([^'"><\s]+)""", RegexOption.IGNORE_CASE)

    // NoveLA patterns, verbatim (order included: window.location.href first).
    private val WINDOW_LOCATION_HREF = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")
    private val WINDOW_LOCATION = Regex("""window\.location\s*=\s*['"]([^'"]+)['"]""")
    private val LOCATION_METHOD = Regex("""location\.(?:replace|assign)\s*\(\s*['"]([^'"]+)['"]""")
    private val LOCATION_HREF = Regex("""location\.href\s*=\s*['"]([^'"]+)['"]""")
    private val LOCATION = Regex("""location\s*=\s*['"]([^'"]+)['"]""")
    private val SCRIPT_LOCATION_PATTERN = Regex("""(?:window\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""")

    // Raw-tag match for `<meta http-equiv="refresh" content="...">` (attribute
    // order either way) — the Document.select("meta[http-equiv=refresh]")
    // half of NoveLA's step 1, without a parser.
    private val META_REFRESH_TAG =
        Regex("""<meta[^>]+http-equiv\s*=\s*["']?refresh["']?[^>]*>""", RegexOption.IGNORE_CASE)
    private val META_CONTENT_ATTR =
        Regex("""content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    // NoveLA resolveRedirectUrl(doc): meta refresh, then location patterns,
    // then the whole-body script sweep. baseUrl plays doc.location()'s role
    // for relative resolution. Returns the absolute, normalized target or
    // null. `javascript:` targets are never navigation and are skipped.
    fun resolveRedirectUrl(html: String, baseUrl: String): String? {
        META_REFRESH_TAG.find(html)?.value
            ?.let { META_CONTENT_ATTR.find(it)?.groupValues?.get(1) }
            ?.let { META_REFRESH_URL.find(it)?.groupValues?.get(1) }
            ?.takeUnless { it.startsWith("javascript:", ignoreCase = true) }
            ?.let { return normalizeUrl(toAbsoluteUrl(baseUrl, it)) }

        for (pattern in listOf(WINDOW_LOCATION_HREF, WINDOW_LOCATION, LOCATION_METHOD, LOCATION_HREF, LOCATION)) {
            pattern.find(html)?.groupValues?.get(1)?.let { return normalizeUrl(toAbsoluteUrl(baseUrl, it)) }
        }

        SCRIPT_LOCATION_PATTERN.find(html)?.groupValues?.get(1)
            ?.let { return normalizeUrl(toAbsoluteUrl(baseUrl, it)) }

        return null
    }

    // NoveLA toAbsoluteUrl(doc, rawUrl): unescapes \/ and resolves relative
    // and protocol-relative URLs against the page URL.
    private fun toAbsoluteUrl(baseUrl: String, rawUrl: String): String {
        var url = rawUrl.replace("\\/", "/").trim()
        if (url.startsWith("javascript:", ignoreCase = true)) return url
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("//")) {
            url = try {
                if (url.startsWith("/")) {
                    val host = baseUrl.substringAfter("://").substringBefore("/")
                    "${baseUrl.substringBefore("://")}://$host$url"
                } else {
                    baseUrl.substringBeforeLast("/") + "/" + url
                }
            } catch (_: Exception) {
                url
            }
        }
        if (url.startsWith("//")) {
            url = try {
                baseUrl.substringBefore("://") + ":" + url
            } catch (_: Exception) {
                "https:$url"
            }
        }
        return url
    }

    // NoveLA normalizeUrl: HTML-entity decoding, \/ unescaping, trimming, and
    // unwrapping redirect wrappers that embed an absolute URL in the path
    // ("https://a.com/x/https://b.com/y" → the last absolute URL).
    private fun normalizeUrl(url: String): String {
        var result = unescapeEntities(url).replace("\\/", "/").trim()
        val idx = maxOf(result.lastIndexOf("https://"), result.lastIndexOf("http://"))
        if (idx > 0) result = result.substring(idx)
        return result
    }

    // Minimal entity decoding for URLs (NoveLA uses jsoup's
    // Parser.unescapeEntities, unavailable here): numeric/hex references plus
    // the named entities that occur in redirect targets.
    private val NUMERIC_ENTITY = Regex("&#(\\d+);")
    private val HEX_ENTITY = Regex("&#x([0-9a-fA-F]+);", RegexOption.IGNORE_CASE)

    private fun unescapeEntities(s: String): String {
        var out = s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        out = HEX_ENTITY.replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }
        out = NUMERIC_ENTITY.replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        return out
    }
}
