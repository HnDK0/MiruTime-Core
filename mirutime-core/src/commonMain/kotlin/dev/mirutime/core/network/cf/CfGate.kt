package dev.mirutime.core.network.cf

import dev.mirutime.core.api.PluginError
import dev.mirutime.core.network.cache.currentTimeMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

// PluginError is a data hierarchy, not Throwable — this bridges CF failures
// out of suspend call sites (PluginHttpClient.get returns HttpResponse).
class CfChallengeRequiredException(val error: PluginError.CfChallengeRequired) :
    IllegalStateException("CF challenge required for host=${error.host}")

// Serializes CF bypass per host and fast-fails repeats via cooldown —
// mirrors the NoveLA interceptor lesson: without cooldown every independent
// request would re-run the solver. NoveLA values
// (CloudfareVerificationInterceptor.kt:48,52): COOLDOWN_MS = 120s (set when a
// solve produced a clearance yet the retry is still challenged — re-solving
// immediately is useless) and TERMINAL_COOLDOWN_MS = 60s (set on terminal
// bypass failure: give-up/abort/timeout). Success clears no cooldown here
// (NoveLA drops it via resolvedDomains); the success cooldown below is this
// gate's "don't re-solve right after a solve" state and carries the 120s
// value, the failure cooldown the terminal 60s.
// Concurrency guarantee: hostLocks/cooldownUntil are guarded by stateMutex
// (suspend helpers), so concurrent callers always share one Mutex per host;
// the caller's block runs outside stateMutex.
class CfGate(
    private val successCooldownMs: Long = 120_000,
    private val failureCooldownMs: Long = 60_000,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    private val stateMutex = Mutex()
    private val hostLocks = mutableMapOf<String, Mutex>()
    private val cooldownUntil = mutableMapOf<String, Long>()
    // True when the cooldown was entered by a FAILED bypass (solver null or
    // retry still challenged); false/absent = success cooldown. The two kinds
    // behave differently under expiredClearance (see withHostLock).
    private val cooldownIsFailure = mutableMapOf<String, Boolean>()

    suspend fun isOnCooldown(host: String): Boolean =
        stateMutex.withLock { clock() < (cooldownUntil[host] ?: 0L) }

    suspend fun enterCooldown(host: String, success: Boolean) {
        val durationMs = if (success) successCooldownMs else failureCooldownMs
        stateMutex.withLock {
            cooldownUntil[host] = clock() + durationMs
            cooldownIsFailure[host] = !success
        }
    }

    private suspend fun hostMutex(host: String): Mutex =
        stateMutex.withLock { hostLocks.getOrPut(host) { Mutex() } }

    // ceiling: on lock-wait timeout TimeoutCancellationException propagates
    // as-is — the caller decides how to surface it.
    // expiredClearance = true when a challenge arrived WHILE a cf_clearance
    // cookie was attached — i.e. the clearance really expired (NoveLA: "cookie
    // present but retry still challenged" re-solves). That bypasses only the
    // SUCCESS cooldown: a failure cooldown means the last solve failed, and
    // re-solving without new information would loop the browser.
    suspend fun <T> withHostLock(
        host: String,
        timeoutMs: Long = 30_000,
        expiredClearance: Boolean = false,
        block: suspend () -> T,
    ): T {
        if (blockedByCooldown(host, expiredClearance)) {
            throw CfChallengeRequiredException(PluginError.CfChallengeRequired(host))
        }
        val mutex = hostMutex(host)
        // Timeout bounds lock acquisition only, never the caller's block.
        withTimeout(timeoutMs) { mutex.lock() }
        try {
            // Re-check under the host lock: the holder we waited for may have
            // just put this host on cooldown — never re-run its solver.
            if (blockedByCooldown(host, expiredClearance)) {
                throw CfChallengeRequiredException(PluginError.CfChallengeRequired(host))
            }
            return block()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun blockedByCooldown(host: String, expiredClearance: Boolean): Boolean {
        val (onCooldown, isFailure) = stateMutex.withLock {
            (clock() < (cooldownUntil[host] ?: 0L)) to (cooldownIsFailure[host] ?: true)
        }
        if (!onCooldown) return false
        // An expired clearance re-solve may bypass the success cooldown only.
        return !(expiredClearance && !isFailure)
    }
}
