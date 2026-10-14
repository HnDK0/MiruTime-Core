package dev.mirutime.core.network

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.util.AttributeKey
import kotlin.time.TimeSource

// Whole-call deadline, enforced by BOUNDARY CHECKS rather than a coroutine
// timer. NoveLA uses withTimeout(90_000) around its retry cycle (NetworkClient
// .kt:79/:180), but arming a real timer at t=0 makes runTest's virtual scheduler
// fast-forward straight to it before the engine starts (it did break 27 tests)
// — see PluginHttpClient.withCallBudget.
// Every hop, every backoff sleep and the CF solver consult this instead.
// clock is injectable with the same shape as CfGate.clock, so tests advance a
// counter by hand rather than waiting out a real 90s.
internal class CallDeadline(
    private val clock: () -> Long,
    private val startedAtMs: Long,
    // null = no deadline: budgeted calls defer to withCallBudget's withTimeout,
    // and defaultDeadlineMs = null opts a caller out entirely.
    private val budgetMs: Long?,
) {
    fun remainingMs(): Long = budgetMs?.let { it - (clock() - startedAtMs) } ?: Long.MAX_VALUE

    // On expiry the caller sees the SAME type withCallBudget produces for an
    // exhausted budget, so every timeout path maps to one "Timeout".
    fun throwIfExpired(url: String) {
        if (remainingMs() <= 0L) throw HttpRequestTimeoutException(url, budgetMs, null)
    }
}

// timeoutMs present -> withCallBudget's withTimeout owns the deadline (budgeted
// calls are runBlocking-tested by design); otherwise the manual boundary
// deadline applies, unless the caller opted out with defaultDeadlineMs = null.
internal fun newCallDeadline(
    clock: () -> Long,
    defaultDeadlineMs: Long?,
    timeoutMs: Long?,
): CallDeadline = CallDeadline(clock, clock(), if (timeoutMs == null) defaultDeadlineMs else null)

// Fallback for clients that install our retry plugins without PluginHttpClient
// (budgetMs = null short-circuits before the clock is read, so the placeholder
// clock below is never invoked).
internal val NO_DEADLINE = CallDeadline({ 0L }, 0L, null)

internal val CallDeadlineAttributeKey = AttributeKey<CallDeadline>("MiruTime.CallDeadline")

// Monotonic elapsed-ms clock: a deadline must not shift when the wall clock is
// adjusted (NTP/DST). Zero at construction; only deltas within one call are read.
internal fun monotonicClockMs(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
