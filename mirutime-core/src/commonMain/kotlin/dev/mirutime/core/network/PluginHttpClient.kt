package dev.mirutime.core.network

import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.CfChallenge
import dev.mirutime.core.host.CfChallengeSolver
import dev.mirutime.core.host.CookieStore
import dev.mirutime.core.host.Logger
import dev.mirutime.core.host.SystemInfo
import dev.mirutime.core.network.cf.CfChallengeRequiredException
import dev.mirutime.core.network.cf.CfGate
import dev.mirutime.core.network.cf.CfOptions
import dev.mirutime.core.network.cf.CloudflareDetector
import dev.mirutime.core.network.cache.CacheKey
import dev.mirutime.core.network.cache.CachedResponse
import dev.mirutime.core.network.cache.ResponseCache
import dev.mirutime.core.network.security.BodySizeLimiter
import dev.mirutime.core.network.security.DnsFailureException
import dev.mirutime.core.network.security.SsrfBlockedException
import dev.mirutime.core.network.security.SsrfGuard
import dev.mirutime.core.network.security.SsrfVerdict
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.call.replaceResponse
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.client.utils.EmptyContent
import io.ktor.util.ContentEncoder
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.util.Attributes
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

// Per-plugin HTTP client: default header pipeline (UA/Accept-Language), a
// manual CookieStore bridge (before request -> Cookie header; after response ->
// Set-Cookie written back, no HttpCookies plugin) and a CF challenge flow:
// detect -> gate (per-host lock + cooldown) -> solve+retry, iteratively up to
// MAX_CF_SOLVE_ATTEMPTS (=2, NoveLA MAX_MANUAL_ATTEMPTS) times.
// Optional SSRF guard (null = check off; host wiring decides later) and a
// streaming response-size limit applied where the body is read.
// Redirects are followed by hand (default on): every hop is re-validated by the
// SSRF guard before the next request goes out — see RedirectFollower.kt.
// Siblings: CallDeadline.kt (whole-call deadline), RedirectFollower.kt (manual
// 3xx walking), PluginHttpClientFactory.kt (platform engine selection),
// cache/ResponseCache.kt (optional host-owned GET cache, NoveLA DiskCache).

// Platform ContentEncoder for Brotli (br) response bodies; the actual lives in
// jvmSharedMain (pure-Java org.brotli:dec decoder, no JNI) and is shared by the
// jvm and android compilations. commonMain cannot see jvmSharedMain directly,
// so it is declared expect/actual — next to its only use, rather than in a
// second BrotliEncoder.kt. internal: it is plumbing for this client only.
internal expect val BrotliEncoder: ContentEncoder

class PluginHttpClient(
    private val metadata: PluginMetadata,
    private val systemInfo: SystemInfo,
    private val cookieStore: CookieStore,
    private val logger: Logger,
    engine: HttpClientEngine,
    private val detector: CloudflareDetector = CloudflareDetector(),
    private val gate: CfGate = CfGate(),
    private val solver: CfChallengeSolver? = null,
    private val ssrfGuard: SsrfGuard? = null,
    private val maxResponseBytes: Long = 25L * 1024 * 1024,
    // Per-host override registry (CF options, UA, Referer): registered at
    // plugin install / engine load, cleared on dispose.
    private val hostOptions: HostOptionsRegistry? = null,
    // Optional response cache (NoveLA DiskCache parity). LIFECYCLE BELONGS TO THE
    // HOST: create one ResponseCache per process, share it across clients, and
    // call invalidateBySource()/clear() from your own code — this client only
    // reads and writes. null (the default) = caching off, which is what every
    // existing caller gets, so no behaviour changes until a host opts in.
    // The host MUST pass the SAME clock source to this client and to the cache:
    // storedAt is stamped here and TTL is validated there, so two different
    // epochs expire every entry immediately (or never).
    private val cache: ResponseCache? = null,
    // Whole-call deadline for calls WITHOUT an explicit timeoutMs. NoveLA wraps
    // every http_get/http_post in withTimeout(90_000) (NetworkClient.kt:79/:180)
    // because its retry structure is multiplicative and CF solving alone can
    // take 50s. null disables it. Enforced as boundary CHECKS, never as a
    // coroutine timer — see CallDeadline.
    private val defaultDeadlineMs: Long? = 90_000,
    // Injectable monotonic clock, same shape as CfGate.clock. Tests advance a
    // counter by hand instead of waiting out a real 90s.
    private val clock: () -> Long = monotonicClockMs(),
    // Optional diagnostic observer, emitted once per network attempt after the
    // body is read (see NetworkTraceEvent). null (default) is the app path and
    // must stay free: no field is computed, no detection pass runs. Wiring is
    // host-side only — LuaPluginSourceFactory passes it straight through.
    private val trace: ((NetworkTraceEvent) -> Unit)? = null,
    // Fresh-network scope driven by the adapter (NoveLA: luaEngine
    // .withForceNetwork around getCatalogList/getCatalogFiltered): when active,
    // the response cache is skipped but still refreshed. The explicit
    // forceNetwork parameter wins per call; this is the ambient equivalent for
    // calls the adapter makes on the plugin's behalf.
    private val forceNetworkActive: (() -> Boolean)? = null,
) {
    private val requestBuilder = PluginRequestBuilder(systemInfo, metadata.userAgentPreset)
    private val bodyLimiter = BodySizeLimiter(maxResponseBytes)
    private val client = HttpClient(engine) {
        // Engine-independent transport timeouts: NoveLA ran 10s for connect and
        // read, while CIO defaults to 15s for the request phase and OkHttp to
        // 10s per phase — without this the behaviour depends on the engine.
        // requestTimeoutMillis stays unset: the whole-call budget in
        // withCallBudget owns the request deadline, across every redirect hop.
        // The retry ladder (sendRequest) overrides these per attempt.
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
        // Transparently decode gzip/deflate/br response bodies. Ktor core does NOT
        // decompress; without this a compressed body arrives raw. Registered from
        // the same list that drives Accept-Encoding in PluginRequestBuilder, so
        // the two can never drift apart.
        install(ContentEncoding) {
            SUPPORTED_CONTENT_ENCODINGS.forEach { encoding ->
                when (encoding) {
                    "gzip" -> gzip()
                    "deflate" -> deflate()
                    "br" -> customEncoder(BrotliEncoder)
                    else -> error("Unsupported content encoding: $encoding")
                }
            }
        }
        // Transport resilience (NoveLA retried IOException for GETs): the
        // TransportRetryLadder Send-loop, not HttpRequestRetry — Ktor reuses
        // one built request for every plugin retry, so per-attempt timeouts
        // cannot ride the plugin config (see TransportRetryLadder.kt).
        // Status retries stay with ServerErrorRetryInterceptor so a 503 is
        // never retried on two axes at once.
        // Status backoff retries (429/502/503/504), after the CF challenge check
        // in shouldRetry so a challenge never consumes an attempt.
        install(TransportRetryLadder)
        install(ServerErrorRetryInterceptor)
        // Redirects are followed manually in fetchFollowingRedirects so the SSRF
        // guard can re-validate EVERY hop (Ktor's built-in follower has no hook).
        followRedirects = false
    }

    suspend fun get(
        sourceId: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long? = null,
        // forceNetwork = NoveLA's "no-cache": skip the cached copy but still
        // refresh it from the network afterwards.
        forceNetwork: Boolean = false,
        followRedirects: Boolean = true,
        method: String = "GET",
    ): HttpResponse {
        logger.d("PluginHttpClient", "$method $url (source=$sourceId)")
        return requestWithCfHandling(
            sourceId, method, url, headers, timeoutMs, followRedirects, null, null, forceNetwork,
        )
    }

    suspend fun post(
        sourceId: String,
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long? = null,
        followRedirects: Boolean = true,
        contentType: String? = null,
    ): HttpResponse {
        logger.d("PluginHttpClient", "POST $url (source=$sourceId)")
        // POST is never cached (cacheKeyFor is GET-only).
        return requestWithCfHandling(
            sourceId, "POST", url, headers, timeoutMs, followRedirects, body, contentType, forceNetwork = false,
        )
    }

    // Shared GET/POST path: cache read -> SSRF gate -> request (+redirects) ->
    // CF detect/solve/retry -> cache write.
    private suspend fun requestWithCfHandling(
        sourceId: String,
        method: String,
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long?,
        followRedirects: Boolean,
        body: String?,
        contentType: String?,
        forceNetwork: Boolean,
    ): HttpResponse {
        // Normalized once: options lookup, gate locks, cooldowns and
        // CfChallenge.host all share the HostOptionsRegistry key scheme.
        val host = normalizeHost(Url(url).host)
        checkSsrf(url, host)

        // GET-only (null for POST) and null when no cache is wired.
        val cacheKey = cacheKeyFor(sourceId, method, url, headers)
        // NoveLA withForceNetwork (catalog paths): skip the cached copy but
        // still refresh it from the network afterwards.
        val force = forceNetwork || forceNetworkActive?.invoke() == true
        if (cacheKey != null && !force) {
            cache?.get(cacheKey)?.let { entry ->
                logger.d("PluginHttpClient", "cache hit $url (source=$sourceId)")
                return cachedResponse(entry, url)
            }
        }

        // One budget for the WHOLE call: every redirect hop, the status-retry
        // backoff and the CF bypass all draw from it — not the per-hop timeout
        // Ktor gives you (NoveLA's BudgetedCall semantics). Captured ONCE here
        // so every boundary below reads the same start instant.
        val deadline = newCallDeadline(clock, defaultDeadlineMs, timeoutMs)
        // One sender for both passes (initial + post-solver retry). The
        // per-call plumbing lives in this closure; fetchFollowingRedirects only
        // sees what varies per hop.
        // hopIndex is reset before EACH fetch pass, so a post-solver retry
        // reports its hops as 0,1,... again instead of continuing a run counter.
        var hopIndex = 0
        val sendHop: SendHop = { hopMethod, hopUrl, hopHeaders, hopBody, hopUserAgent ->
            val hopStartedAtMs = clock()
            val hop = sendRequest(
                hopMethod, hopUrl, hopHeaders, timeoutMs, deadline, host, hopBody, contentType, hopUserAgent,
            )
            val hopBytes = bodyLimiter.readWithinLimit(hop.bodyAsChannel())
            val hopNumber = hopIndex++
            emitTrace(hopStartedAtMs, hopMethod, hopUrl, hop, hopBytes, hopNumber)
            hop to hopBytes
        }
        return withCallBudget(url, timeoutMs) {
            hopIndex = 0
            val firstPass = fetchFollowingRedirects(
                sendHop, ::checkSsrf, deadline, method, url, headers, host, followRedirects, body,
            )
            var response = firstPass.first
            var bytes = firstPass.second
            if (!isChallenge(response, bytes, host)) {
                storeIfCacheable(cacheKey, response.status.value, response.headers, bytes)
                return@withCallBudget rebuildResponse(response, bytes)
            }

            logger.w("PluginHttpClient", "CF challenge on $host (status=${response.status.value})")
            // Effective Referer the request will carry (caller header or the
            // scheme://host/ default) — NoveLA reads originalRequest's Referer
            // the same way (its defaultHeaders always set one).
            val referer = headers.entries
                .firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
                ?: refererFromUrl(url)
            val solver = solver
                ?: throw CfChallengeRequiredException(PluginError.CfChallengeRequired(host))

            // A challenge that arrived WHILE cf_clearance was attached means the
            // clearance genuinely expired — NoveLA re-solves in that case even if
            // a success cooldown is active. Heuristic ceiling: the store may also
            // hold a cf_clearance written by the challenge response itself (via
            // Set-Cookie on this very fetch), which would false-positive here;
            // the gate's host mutex + failure cooldown still bound the damage.
            val expiredClearance = cookieStore.get(url).any { it.name == "cf_clearance" }
            gate.withHostLock(host, expiredClearance = expiredClearance) {
                // NoveLA parity (MAX_MANUAL_ATTEMPTS=2): up to TWO solve+retry
                // rounds inside ONE host lock. Iterative, never recursive — the
                // third challenge fails hard and the gate's failure cooldown keeps
                // every later request to this host on the fast path.
                var attempts = 0
                while (isChallenge(response, bytes, host)) {
                    if (++attempts > MAX_CF_SOLVE_ATTEMPTS) {
                        gate.enterCooldown(host, success = false)
                        throw CfChallengeRequiredException(PluginError.CfChallengeRequired(host))
                    }
                    // NoveLA clears ONLY cf_clearance before a fresh manual
                    // bypass (clearCookiesForDomain, Max-Age=0): a clearance CF
                    // just rejected must not be re-sent with the next solve.
                    // Non-CF cookies (sort order, locale) survive — the server
                    // re-creates only what the selective delete removed.
                    if (attempts > 1) {
                        cookieStore.clearCookies(host, listOf("cf_clearance"))
                        logger.w(
                            "PluginHttpClient",
                            "CF attempt $attempts/$MAX_CF_SOLVE_ATTEMPTS on $host (cf_clearance cleared)",
                        )
                    }
                    // CF solving alone may take 50s in NoveLA — check on both sides
                    // so a solver that blows the budget stops here, not on hop 2.
                    deadline.throwIfExpired(url)
                    val solution = solver.solve(
                        CfChallenge(
                            host = host,
                            url = url,
                            status = response.status.value,
                            markers = matchedMarkers(host, bytes.decodeToString()),
                            referer = referer,
                        ),
                    )
                    deadline.throwIfExpired(url)
                    if (solution == null) {
                        gate.enterCooldown(host, success = false)
                        throw CfChallengeRequiredException(PluginError.CfChallengeRequired(host))
                    }
                    if (solution.cookies.isNotEmpty()) {
                        cookieStore.setFromResponse(
                            url,
                            solution.cookies.map { (name, value) -> "$name=$value" },
                        )
                    }
                    // Retry with the solver's cookies AND user agent. Set-Cookie is
                    // bridged on EVERY hop: sendRequest runs the cookie bridge per
                    // request, so intermediate redirect responses reach the store
                    // as well — not only the final one.
                    hopIndex = 0
                    val retry = fetchFollowingRedirects(
                        sendHop, ::checkSsrf, deadline, method, url, headers, host, followRedirects, body,
                        solution.userAgent,
                    )
                    response = retry.first
                    bytes = retry.second
                }
                gate.enterCooldown(host, success = true)
                storeIfCacheable(cacheKey, response.status.value, response.headers, bytes)
                rebuildResponse(response, bytes)
            }
        }
    }

    // NoveLA bounded the WHOLE call rather than each hop: an explicit timeoutMs
    // is the caller's budget, and it must span every redirect hop, the
    // status-retry backoff and the CF bypass — a per-hop `timeout {}` re-arms on
    // each hop, so N hops silently multiply the caller's budget by N.
    // An exhausted budget surfaces as Ktor's HttpRequestTimeoutException — the
    // type callers already map to "Timeout" — instead of escaping as a raw
    // coroutine cancellation.
    // Calls WITHOUT timeoutMs are bounded by CallDeadline's boundary checks
    // instead (see newCallDeadline): a real coroutine timer here would arm at
    // t=0 and runTest's virtual scheduler would fast-forward straight to it
    // before the engine ever starts — that is exactly what broke 27 tests.
    private suspend fun <T> withCallBudget(url: String, timeoutMs: Long?, block: suspend () -> T): T {
        if (timeoutMs == null) return block()
        return try {
            withTimeout(timeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            throw HttpRequestTimeoutException(url, timeoutMs, e)
        }
    }

    private fun checkSsrf(url: String, host: String) {
        ssrfGuard?.let { guard ->
            when (val verdict = guard.isSafe(url)) {
                is SsrfVerdict.Blocked -> throw SsrfBlockedException(verdict.reason)
                SsrfVerdict.DnsFailure -> throw DnsFailureException(host)
                SsrfVerdict.Ok -> Unit
            }
        }
    }

    private suspend fun sendRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long?,
        deadline: CallDeadline,
        host: String,
        body: String?,
        contentType: String?,
        userAgentOverride: String?,
    ): HttpResponse {
        val cookies = cookieStore.get(url)
        return client.request(url) {
            this.method = when (method) {
                "HEAD" -> HttpMethod.Head
                "POST" -> HttpMethod.Post
                else -> HttpMethod.Get
            }
            // Order: bridged cookies first, then defaults + caller extras via
            // headers.set — a caller-provided Cookie header wins over the bridge.
            if (cookies.isNotEmpty()) {
                this.headers.set(
                    HttpHeaders.Cookie,
                    cookies.joinToString("; ") { "${it.name}=${it.value}" },
                )
            }
            // Per-host UA override (registered at install, cleared on dispose)
            // WINS over the plugin's static metadata.userAgentPreset; a CF
            // solution's UA (retry path) is the strongest signal — applied last
            // via userAgentOverride. Passed into applyTo so the sec-ch-ua*
            // family is derived from the UA actually sent, never from a
            // contradicting preset.
            // Per-host Referer override (the plugin's `referer` global,
            // NoveLA SourceInterface.referer): applies only when the caller
            // sent no explicit Referer.
            requestBuilder.applyTo(
                this,
                headers,
                userAgentOverride ?: hostOptions?.userAgent(host),
                hostOptions?.referer(host),
            )
            if (body != null) {
                setBody(TextContent(body, ContentType.parse(contentType ?: "text/plain")))
            }
            // Both retry plugins read the whole-call deadline from here, the
            // same way they read the budget flag below.
            attributes.put(CallDeadlineAttributeKey, deadline)
            if (timeoutMs != null) {
                // Whole-call budget active: exactly one attempt, no backoff
                // (NoveLA BudgetedCall) — read by ServerErrorRetryInterceptor.
                // The deadline itself comes from withCallBudget, NOT from a
                // per-hop timeout{}: re-arming per hop would hand each hop its
                // own full budget, so N hops multiply the caller's by N.
                attributes.put(BudgetedCallAttributeKey, true)
            }
        }.also { response ->
            val setCookies = response.headers.getAll(HttpHeaders.SetCookie)
            if (!setCookies.isNullOrEmpty()) {
                cookieStore.setFromResponse(url, setCookies)
            }
        }
    }

    // bodyAsBytes consumed the original channel — re-wrap so callers can
    // still read the body of a non-challenge response.
    private fun rebuildResponse(response: HttpResponse, bytes: ByteArray): HttpResponse =
        response.call.replaceResponse { ByteReadChannel(bytes) }.response

    private fun isChallenge(response: HttpResponse, bytes: ByteArray, host: String): Boolean =
        detector.isChallenge(
            response.status.value,
            bytes.decodeToString(),
            cfOptionsFor(host),
            response.headers[HttpHeaders.Server],
            response.headers[CF_MITIGATED],
            // NoveLA checks response.request.url (the final hop after
            // redirects): drives the static-asset skip, the built-in host
            // whitelist and the IP-block markers. Without it those three
            // never fire on the real path.
            requestUrl = response.request.url.toString(),
        )

    // Diagnostic observer: returns immediately when no sink is wired, so the
    // app path never pays for header flattening or the second detector pass.
    // Ceiling: a hop whose send throws (timeout/SSRF/DNS) produces no event —
    // there is no body to report, and the exception already names the failure.
    private fun emitTrace(
        startedAtMs: Long,
        method: String,
        url: String,
        response: HttpResponse,
        bytes: ByteArray,
        hop: Int,
    ) {
        val sink = trace ?: return
        sink(
            NetworkTraceEvent(
                method = method,
                url = url,
                status = response.status.value,
                requestHeaders = response.request.headers.entries()
                    .associate { (name, values) -> name to values.joinToString(", ") },
                // Lowercased: NetworkBindings hands plugins lowercase response
                // header names, and the tester reads them the same way.
                responseHeaders = buildMap {
                    for (name in response.headers.names()) {
                        val key = name.lowercase()
                        put(key, get(key).orEmpty() + response.headers.getAll(name).orEmpty())
                    }
                },
                bodyBytes = bytes.size.toLong(),
                durationMs = clock() - startedAtMs,
                cfBlocked = detector.isChallenge(
                    response.status.value,
                    bytes.decodeToString(),
                    cfOptionsFor(normalizeHost(Url(url).host)),
                    response.headers[HttpHeaders.Server],
                    response.headers[CF_MITIGATED],
                    requestUrl = response.request.url.toString(),
                ),
                redirectHop = hop,
            ),
        )
    }

    private fun matchedMarkers(host: String, body: String): List<String> {
        val markers = cfOptionsFor(host)?.markers ?: CfOptions().markers
        return markers.filter { body.contains(it, ignoreCase = true) }
    }

    // Per-host override (registered at plugin install, cleared on dispose)
    // WINS over this plugin's static metadata.cfOptions.
    private fun cfOptionsFor(host: String): CfOptions? =
        hostOptions?.cfOptions(host) ?: metadata.cfOptions

    // GET-only: a POST body is never replayed from cache. Returns null when no
    // cache is wired, which keeps the whole path a no-op for existing callers.
    // Key = method + url + sourceId + the headers that can change the body:
    // the caller's own set (hashed by CacheKey) plus the two WE resolve per call
    // — the effective per-host UA and the Accept-Encoding list — so a per-host
    // UA override or an encoder change can never serve a stale body.
    // ceiling: no Vary handling — a server varying on other headers may serve a
    // body that ignores them; charset is unused because we store raw bytes.
    private fun cacheKeyFor(sourceId: String, method: String, url: String, headers: Map<String, String>): CacheKey? {
        if (cache == null || !method.equals("GET", ignoreCase = true)) return null
        val effective = headers + mapOf(
            HttpHeaders.UserAgent to requestBuilder.resolveUserAgent(runCatching { normalizeHost(Url(url).host) }
                .getOrDefault("")),
            HttpHeaders.AcceptEncoding to ACCEPT_ENCODING,
        )
        return CacheKey.from("GET", url, null, sourceId, effective)
    }

    // Only a clean, non-challenge 200 is worth keeping; a 3xx/4xx/5xx or a CF
    // challenge must revalidate against the origin next time.
    private fun storeIfCacheable(key: CacheKey?, status: Int, headers: Headers, bytes: ByteArray) {
        val target = cache ?: return
        if (key == null || status != 200) return
        val stored = buildMap {
            headers.forEach { name, values -> put(name.lowercase(), values) }
        }
        target.put(key, CachedResponse(status, stored, bytes, clock()))
    }

    // Rebuilds a cached entry as a real HttpResponse without touching the
    // engine — the same HttpRequestData/HttpResponseData pair MockEngine uses.
    // The body was stored AFTER ContentEncoding decoded it, so no plugin runs
    // here: what comes back is byte-identical to what the network produced.
    // Ktor marks those data classes @InternalAPI: opting in is the documented
    // escape hatch and is exactly what MockEngine does internally.
    @OptIn(InternalAPI::class)
    private suspend fun cachedResponse(entry: CachedResponse, url: String): HttpResponse {
        val call = HttpClientCall(
            client,
            HttpRequestData(
                url = Url(url),
                method = HttpMethod.Get,
                headers = headersOf(),
                // A GET carries no body; Ktor's own EmptyContent is the null
                // object for a non-null OutgoingContent slot.
                body = EmptyContent,
                executionContext = Job(),
                attributes = Attributes(),
            ),
            HttpResponseData(
                statusCode = HttpStatusCode.fromValue(entry.status),
                requestTime = GMTDate(),
                headers = headersOf(*entry.headers.map { (name, values) -> name to values }.toTypedArray()),
                version = HttpProtocolVersion.HTTP_1_1,
                body = ByteReadChannel(entry.body),
                callContext = coroutineContext,
            ),
        )
        return call.response
    }
}

internal const val CF_MITIGATED = "cf-mitigated"

// CF solve+retry rounds allowed inside ONE request (NoveLA
// CloudfareVerificationInterceptor.MAX_MANUAL_ATTEMPTS = 2): the first bypass
// plus one fresh re-solve after the rejected clearance. Iterative bound — the
// third challenge fails hard, never loops.
private const val MAX_CF_SOLVE_ATTEMPTS = 2

// Single source of truth for response compression: PluginHttpClient registers
// exactly these encoders in ContentEncoding and PluginRequestBuilder advertises
// exactly this list in Accept-Encoding — one list, so the two cannot drift.
// Parity: the wire value stays "gzip, deflate, br" (NoveLA).
internal val SUPPORTED_CONTENT_ENCODINGS: List<String> = listOf("gzip", "deflate", "br")
internal val ACCEPT_ENCODING: String = SUPPORTED_CONTENT_ENCODINGS.joinToString(", ")
