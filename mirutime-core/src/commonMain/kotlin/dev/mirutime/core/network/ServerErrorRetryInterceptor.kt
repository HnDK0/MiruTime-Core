package dev.mirutime.core.network

import dev.mirutime.core.network.cf.CloudflareDetector
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.discardRemaining
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.util.AttributeKey
import kotlinx.coroutines.delay

// Set by PluginHttpClient when the call runs under a whole-call budget
// (timeoutMs): NoveLA's BudgetedCall does exactly ONE attempt — no backoff —
// so the budget can never be spent sleeping between retries.
internal val BudgetedCallAttributeKey = AttributeKey<Boolean>("MiruTime.BudgetedCall")

// Retries transient server errors (429/502/503/504) with exponential backoff.
// Parity with NoveLA's ServerErrorRetryInterceptor: GET-only, maxRetries = 4,
// 500ms base backoff. Installed on the per-plugin client in PluginHttpClient;
// internal because it is that client's plumbing, not a consumer-facing plugin.
internal val ServerErrorRetryInterceptor = createClientPlugin("ServerErrorRetryInterceptor") {
    val maxRetries = 4
    val initialBackoffMs = 500L

    on(Send) { request ->
        // Only idempotent GETs are replayed — a POST body may not be replayable.
        if (request.method != HttpMethod.Get) return@on proceed(request)
        // Budgeted call: one attempt, never backoff (NoveLA BudgetedCall).
        if (request.attributes.getOrNull(BudgetedCallAttributeKey) == true) {
            return@on proceed(request)
        }
        val deadline = request.attributes.getOrNull(CallDeadlineAttributeKey) ?: NO_DEADLINE

        var call = proceed(request)
        var attempt = 0
        while (attempt < maxRetries && shouldRetry(call.response)) {
            // Close the failed response before re-sending (frees the connection).
            call.response.discardRemaining()
            // Sleep at most what the whole-call deadline still allows; an
            // already-spent deadline yields a <=0 delay and throws below
            // instead of waking up late.
            delay(minOf(initialBackoffMs * (1L shl attempt), deadline.remainingMs()))
            attempt++
            deadline.throwIfExpired(request.url.toString())
            call = proceed(request)
        }
        call
    }
}

// A CF challenge is NOT a transient server error: backoff-retrying it burns the
// whole attempt budget against the challenge endpoint and delays the detector
// and solver, which only ever see the final response. NoveLA keeps its CF
// interceptor OUTSIDE the retry layer for the same reason — so a challenge is
// solved on attempt 1 here too, instead of after ~7.5s of pointless retries.
// ceiling: only headers are readable at this point — consuming the body would
// break streaming — so a challenge identified by body markers alone, or by a
// plugin's custom cfOptions.statuses, still retries. CloudflareDetector stays
// the authority and takes over the moment this returns the response.
private fun shouldRetry(response: HttpResponse): Boolean {
    if (response.status.value !in RETRYABLE_CODES) return false
    return !CloudflareDetector.isChallengeByHeaders(
        status = response.status.value,
        serverHeader = response.headers[HttpHeaders.Server],
        cfMitigatedHeader = response.headers[CF_MITIGATED],
    )
}

// ponytail: 502/503/504 — transient CDN errors; 429 — CDN rate limit
// (CF challenges are handled separately by the detector/gate).
private val RETRYABLE_CODES = setOf(429, 502, 503, 504)
