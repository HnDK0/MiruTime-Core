package dev.mirutime.core.network

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom

// Single-hop sender supplied by PluginHttpClient. Everything that varies per
// CALL (timeoutMs, contentType, budget flag) stays in its closure, so this seam
// only exposes what varies per HOP: method, URL, per-hop headers, body, UA
// override. The pair carries the response ALREADY body-limited: the size limiter
// runs between "send" and "decide", exactly where the loop used to run it itself.
internal typealias SendHop = suspend (
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String?,
    userAgentOverride: String?,
) -> Pair<HttpResponse, ByteArray>

// Walks 3xx hops by hand (default on). Ktor's built-in follower has no per-hop
// hook, so the SSRF guard must re-validate EVERY target before the next request
// goes out — that is the whole reason this loop exists.
// Policy lives here: hop counting, Location resolution, browser-shaped method
// downgrade and per-hop header hygiene. Transport lives behind sendHop.
internal suspend fun fetchFollowingRedirects(
    sendHop: SendHop,
    checkSsrf: (url: String, host: String) -> Unit,
    deadline: CallDeadline,
    method: String,
    url: String,
    headers: Map<String, String>,
    host: String,
    followRedirects: Boolean,
    body: String?,
    userAgentOverride: String? = null,
    // JS/meta navigation following (JsRedirectResolver): an HTML GET response
    // carrying a meta-refresh or location= navigation is followed like a 3xx,
    // SSRF-validated and bounded by the same MAX_REDIRECTS. Follows the
    // followRedirects flag by default — a caller resolving hosters from a raw
    // 3xx (followRedirects=false) gets no JS hop either.
    followJsRedirects: Boolean = followRedirects,
): Pair<HttpResponse, ByteArray> {
    var currentUrl = url
    var currentMethod = method
    var currentHeaders = headers
    var currentBody = body
    var redirects = 0
    while (true) {
        // Boundary (a): never start a hop the deadline cannot pay for.
        deadline.throwIfExpired(currentUrl)
        val (response, bytes) = sendHop(currentMethod, currentUrl, currentHeaders, currentBody, userAgentOverride)
        if (!followRedirects) return response to bytes
        if (response.status.value in REDIRECT_CODES) {
            val location = response.headers[HttpHeaders.Location] ?: return response to bytes
            if (redirects >= MAX_REDIRECTS) return response to bytes
            val nextUrl = try {
                URLBuilder().takeFrom(currentUrl).takeFrom(location).build().toString()
            } catch (e: Exception) {
                return response to bytes
            }
            // Security: never follow a hop into a private/metadata target.
            checkSsrf(nextUrl, runCatching { normalizeHost(Url(nextUrl).host) }.getOrDefault(host))
            // 303 asks for a GET; 301/302 degrade a body-carrying method (browser
            // behaviour); 307/308 preserve method and body.
            // OkHttp — NoveLA's Android engine — PRESERVES HEAD on every status, so
            // the downgrade must never touch a method that carries no body.
            val degrades =
                response.status.value == 303 ||
                    (currentBody != null && response.status.value != 307 && response.status.value != 308)
            val dropsBody = degrades && currentMethod != "GET" && currentMethod != "HEAD"
            if (dropsBody) {
                currentMethod = "GET"
                currentBody = null
            }
            currentHeaders = redirectHeaders(currentHeaders, from = currentUrl, to = nextUrl, dropsBody = dropsBody)
            currentUrl = nextUrl
            redirects++
            continue
        }
        // JS navigation hop (NoveLA JsRedirectResolver, used by its
        // DownloaderRepository on chapter pages): a 200 HTML page that is only
        // a "Loading..." wrapper around window.location/meta-refresh. GET +
        // HTML only, Referer = the page it came from (NoveLA sets Referer:
        // realUrl on the manual hop), same SSRF + MAX_REDIRECTS bounds.
        // ceiling: pattern-matched — see JsRedirectResolver.
        val jsTarget = if (followJsRedirects) jsRedirectTarget(response, bytes, currentUrl, currentMethod) else null
        if (jsTarget == null) return response to bytes
        if (redirects >= MAX_REDIRECTS) return response to bytes
        checkSsrf(jsTarget, runCatching { normalizeHost(Url(jsTarget).host) }.getOrDefault(host))
        currentHeaders = currentHeaders + mapOf("Referer" to currentUrl)
        currentUrl = jsTarget
        redirects++
    }
}

// Non-null exactly when this hop should continue via a JS navigation:
// GET, HTML body, resolver found a non-self http(s) target.
private fun jsRedirectTarget(
    response: HttpResponse,
    bytes: ByteArray,
    currentUrl: String,
    currentMethod: String,
): String? {
    if (currentMethod != "GET") return null
    if (response.headers[HttpHeaders.ContentType]?.contains("text/html", ignoreCase = true) != true) return null
    val target = JsRedirectResolver.resolveRedirectUrl(bytes.decodeToString(), currentUrl)
        ?.takeUnless { it.startsWith("javascript:", ignoreCase = true) }
        ?: return null
    if (target == currentUrl) return null
    return target
}

// OkHttp followUpRequest parity. A cross-origin hop (scheme/host/port) must not
// carry the caller's Authorization any further, and a hop whose body was
// dropped must not keep its entity headers.
// ceiling: only CALLER-supplied headers are filtered here — Cookie, UA and the
// Chrome header set are re-applied per hop by sendRequest, and per-host UA
// overrides are intentionally origin-independent.
internal fun redirectHeaders(
    headers: Map<String, String>,
    from: String,
    to: String,
    dropsBody: Boolean,
): Map<String, String> {
    if (headers.isEmpty()) return headers
    val crossOrigin = !sameOrigin(from, to)
    return headers.filterKeys { name ->
        when {
            crossOrigin && name.equals(HttpHeaders.Authorization, ignoreCase = true) -> false
            dropsBody && name in BODY_HEADERS -> false
            else -> true
        }
    }
}

private val BODY_HEADERS = setOf(
    HttpHeaders.ContentType,
    HttpHeaders.ContentLength,
    HttpHeaders.TransferEncoding,
)

private fun sameOrigin(from: String, to: String): Boolean {
    val a = runCatching { Url(from) }.getOrNull() ?: return true
    val b = runCatching { Url(to) }.getOrNull() ?: return true
    return a.protocol == b.protocol && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
}

// Redirect status codes followed by fetchFollowingRedirects.
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
private const val MAX_REDIRECTS = 20
