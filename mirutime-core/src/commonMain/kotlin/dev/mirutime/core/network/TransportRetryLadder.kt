package dev.mirutime.core.network

import dev.mirutime.core.network.security.SsrfBlockedException
import dev.mirutime.core.network.security.DnsFailureException
import dev.mirutime.core.network.security.ResponseTooLargeException
import dev.mirutime.core.network.cf.CfChallengeRequiredException
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.timeout
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.random.Random

// NoveLA retry ladder (NetworkClient.kt:87,180-215): up to 5 attempts —
// short early ones to drop a dead connection fast, one long tail for a
// slow-but-alive host — with 0-400ms jitter between attempts so parallel
// requests (catalog + covers) don't hammer the edge in lockstep. GET-only
// (a POST body may not be replayable); a budgeted call is exactly ONE
// attempt (NoveLA BudgetedCall). A spent whole-call deadline stops the
// ladder instead of sleeping past it.
//
// A manual Send-loop, not HttpRequestRetry: Ktor reuses one built request
// for every plugin retry, so per-attempt timeouts cannot ride the plugin
// config. The engine reads HttpTimeoutCapability on EVERY execution, so
// replacing it here retimes each retry (a builder timeout{} is fixed at
// build time and could not vary per attempt). Installed where
// HttpRequestRetry's exception axis used to sit — outside
// ServerErrorRetryInterceptor, so a 503 is never retried on two axes at
// once, at the same pipeline depth the old predicate ran (same timing).
//
// ceiling: NoveLA evicts the connection pool (evictAll) before each retry
// so a poisoned h2 connection is never reused — Ktor's engine abstraction
// exposes no pool handle, so there is no portable equivalent here. A
// timed-out attempt's connection is dead by definition and never re-enters
// any pool; only the challenge-poisoned-keepalive case is uncovered.
// Internal: PluginHttpClient installs it; the values are pinned by
// PluginHttpClientBackoffTest.
internal val TransportRetryLadder = createClientPlugin("TransportRetryLadder") {
    on(Send) { request ->
        // Only idempotent GETs are replayed — a POST body may not be replayable.
        if (request.method != HttpMethod.Get) return@on proceed(request)
        // Budgeted call: one attempt, never backoff (NoveLA BudgetedCall).
        if (request.attributes.getOrNull(BudgetedCallAttributeKey) == true) {
            return@on proceed(request)
        }
        val deadline = request.attributes.getOrNull(CallDeadlineAttributeKey) ?: NO_DEADLINE

        var attempt = 0
        var call: HttpClientCall? = null
        while (call == null) {
            // Per-attempt transport phases for THIS send only.
            request.timeout {
                connectTimeoutMillis = ATTEMPT_TIMEOUTS_MS[attempt]
                socketTimeoutMillis = ATTEMPT_TIMEOUTS_MS[attempt]
            }
            try {
                call = proceed(request)
            } catch (e: CancellationException) {
                // External cancellation (navigation, scope death) — propagate.
                throw e
            } catch (e: Exception) {
                // A ladder timeout (HttpRequestTimeoutException from the
                // per-attempt phases above) is retryable by construction: the
                // phase fired because THIS attempt was too slow, not because
                // the call ran out of budget (budgeted calls never ladder).
                val retryable = e is HttpRequestTimeoutException || e.isRetryableTransportFailure()
                if (!retryable || attempt + 1 >= ATTEMPT_TIMEOUTS_MS.size || deadline.remainingMs() <= 0L) throw e
                // Sleep at most what the whole-call deadline still allows (the
                // old HttpRequestRetry delayMillis clamp, kept): an uncapped
                // sleep would overshoot a small host budget instead of landing
                // the legal retry inside it.
                delay(minOf(Random.nextLong(0, RETRY_JITTER_MS + 1), deadline.remainingMs()))
                attempt++
            }
        }
        call
    }
}

// Transport-failure ladder (NoveLA NetworkClient.kt:81-87): asymmetric
// per-attempt timeouts — short early attempts drop a hung/dead connection
// from the pool fast during mass chapter loads, the long tail covers a slow
// but alive host or an unlucky streak. 4x3s + 15s = 27s, under the 90s outer
// deadline. 5 attempts: p^5 ~= 3% miss chance vs ~12% on 3 attempts.
// internal: the retry-ladder test pins the values against regressions.
internal val ATTEMPT_TIMEOUTS_MS = longArrayOf(3_000, 3_000, 3_000, 3_000, 15_000)

// Jitter before a ladder retry (NoveLA NetworkClient.kt:89-93, 0-400ms):
// spreads simultaneous retries (catalog + covers failing together on one
// edge) in time instead of hitting the edge synchronously.
internal const val RETRY_JITTER_MS = 400L

// Whether a failed send is worth another transport attempt. commonMain cannot
// name java.io.IOException, so the predicate is inverted: retry everything that
// is not a known domain failure, a timeout, or a cancellation — RST,
// connection-reset and socket-timeout all land on the retryable side.
// ceiling: an unexpected IllegalStateException from a plugin also gets up to
// ATTEMPT_TIMEOUTS_MS.size attempts.
private fun Throwable.isRetryableTransportFailure(): Boolean =
    this !is CancellationException &&
        this !is HttpRequestTimeoutException &&
        this !is SsrfBlockedException &&
        this !is DnsFailureException &&
        this !is CfChallengeRequiredException &&
        this !is ResponseTooLargeException
