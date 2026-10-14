package dev.mirutime.core.network.cf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CfGateTest {

    @Test
    fun parallelCallsOnSameHostSerialize() = runTest {
        val gate = CfGate()
        val order = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val job1 = launch {
            gate.withHostLock("h") {
                order += "a-start"
                firstStarted.complete(Unit)
                releaseFirst.await()
                order += "a-end"
            }
        }
        firstStarted.await()

        val job2 = launch {
            gate.withHostLock("h") { order += "b" }
        }
        yield()
        assertFalse(order.contains("b"), "second caller must wait for the host lock")

        releaseFirst.complete(Unit)
        job1.join()
        job2.join()
        assertEquals(listOf("a-start", "a-end", "b"), order)
    }

    @Test
    fun differentHostsDoNotBlockEachOther() = runTest {
        val gate = CfGate()
        val order = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val job1 = launch {
            gate.withHostLock("host-a") {
                order += "a-start"
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()

        gate.withHostLock("host-b") { order += "b" }
        assertEquals(listOf("a-start", "b"), order)

        releaseFirst.complete(Unit)
        job1.join()
    }

    @Test
    fun activeCooldownFailsFastWithoutRunningBlock() = runTest {
        val gate = CfGate()
        gate.enterCooldown("h", success = false)

        var blockRan = false
        val error = assertFailsWith<CfChallengeRequiredException> {
            gate.withHostLock("h") { blockRan = true }
        }
        assertEquals("h", error.error.host)
        assertFalse(blockRan)
    }

    @Test
    fun successAndFailureCooldownsAreIndependent() = runTest {
        var now = 0L
        val gate = CfGate(successCooldownMs = 60_000, failureCooldownMs = 300_000) { now }

        gate.enterCooldown("ok-host", success = true)
        gate.enterCooldown("bad-host", success = false)

        now = 60_000
        // Success cooldown expired -> lock acquired.
        gate.withHostLock("ok-host") { /* pass */ }
        // Failure cooldown (300s) still active at t=60s.
        assertFailsWith<CfChallengeRequiredException> {
            gate.withHostLock("bad-host") { /* must not run */ }
        }

        now = 300_000
        gate.withHostLock("bad-host") { /* pass */ }
    }

    @Test
    fun expiredClearanceBypassesSuccessCooldownButNotFailure() = runTest {
        var now = 0L
        val gate = CfGate(successCooldownMs = 60_000, failureCooldownMs = 300_000) { now }
        gate.enterCooldown("ok-host", success = true)
        gate.enterCooldown("bad-host", success = false)

        // Challenge arrived with cf_clearance attached (true expiry): the
        // success cooldown must not block a re-solve.
        gate.withHostLock("ok-host", expiredClearance = true) { /* pass */ }

        // Without an expired clearance the success cooldown still fast-fails.
        var blockedRan = false
        assertFailsWith<CfChallengeRequiredException> {
            gate.withHostLock("ok-host") { blockedRan = true }
        }
        assertFalse(blockedRan)

        // A FAILED bypass's cooldown is absolute — re-solving without new
        // information would loop the browser.
        var failureRan = false
        assertFailsWith<CfChallengeRequiredException> {
            gate.withHostLock("bad-host", expiredClearance = true) { failureRan = true }
        }
        assertFalse(failureRan)
    }

    @Test
    fun waitingForLockTimesOutInsteadOfHangingForever() = runTest {
        val gate = CfGate()
        val firstStarted = CompletableDeferred<Unit>()
        val holdForever = CompletableDeferred<Unit>()

        val job1 = launch {
            gate.withHostLock("h", timeoutMs = 60_000) {
                firstStarted.complete(Unit)
                holdForever.await()
            }
        }
        firstStarted.await()

        assertFailsWith<TimeoutCancellationException> {
            gate.withHostLock("h", timeoutMs = 50) { /* never reached */ }
        }

        holdForever.complete(Unit)
        job1.join()
    }

    @Test
    fun lockIsReleasedAfterBlockThrows() = runTest {
        val gate = CfGate()
        assertFailsWith<IllegalStateException> {
            gate.withHostLock("h") { throw IllegalStateException("boom") }
        }
        // Re-acquire proves the mutex was released.
        gate.withHostLock("h") { /* pass */ }
    }

    @Test
    fun cooldownSetInsideLockFastFailsWaitingCaller() = runTest {
        val gate = CfGate()
        var blockRuns = 0
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val job1 = launch {
            gate.withHostLock("h") {
                firstStarted.complete(Unit)
                releaseFirst.await()
                // Simulates a failed bypass setting the failure cooldown.
                gate.enterCooldown("h", success = false)
            }
        }
        firstStarted.await()

        var waitingCallerFastFailed = false
        val job2 = launch {
            try {
                // "Fake solver": must never run — caller 1 put the host on cooldown.
                gate.withHostLock("h") { blockRuns++ }
            } catch (_: CfChallengeRequiredException) {
                waitingCallerFastFailed = true
            }
        }
        // Let job2 reach the lock wait before job1 releases the lock.
        // yield (not advanceUntilIdle): advancing virtual time would expire
        // job2's withTimeout before job1 sets the cooldown.
        yield()

        releaseFirst.complete(Unit)
        job1.join()
        job2.join()

        assertEquals(0, blockRuns, "waiting caller must re-check cooldown under the lock")
        assertTrue(waitingCallerFastFailed, "waiting caller must get the cooldown fast-fail")
    }

    // NoveLA defaults (CloudfareVerificationInterceptor.kt:48,52):
    // COOLDOWN_MS = 120s, TERMINAL_COOLDOWN_MS = 60s.
    @Test
    fun defaultCooldownsMatchNoveLa() = runTest {
        var now = 0L
        val gate = CfGate(clock = { now })

        gate.enterCooldown("ok-host", success = true)
        gate.enterCooldown("bad-host", success = false)

        // Success cooldown (120s): 119s still blocked, 121s free.
        now = 119_000
        assertFailsWith<CfChallengeRequiredException> {
            gate.withHostLock("ok-host") { /* must not run */ }
        }
        now = 121_000
        gate.withHostLock("ok-host") { /* pass */ }

        // Failure (terminal) cooldown (60s): 59s still blocked, 61s free.
        now = 59_000
        assertFailsWith<CfChallengeRequiredException> {
            gate.withHostLock("bad-host") { /* must not run */ }
        }
        now = 61_000
        gate.withHostLock("bad-host") { /* pass */ }
    }
}
