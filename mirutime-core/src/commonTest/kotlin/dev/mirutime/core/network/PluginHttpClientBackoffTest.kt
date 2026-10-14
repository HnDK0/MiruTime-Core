package dev.mirutime.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// NoveLA retry ladder (NetworkClient.kt:81-87), ported into sendRequest:
// asymmetric per-attempt timeouts — short early attempts drop a hung/dead
// connection fast, one long tail for a slow-but-alive host. Values are pinned
// here so a silent change cannot move every transport retry.
class PluginHttpClientBackoffTest {

    @Test
    fun ladderIsFourQuickAttemptsPlusOneLongTail() {
        assertEquals(listOf(3_000L, 3_000L, 3_000L, 3_000L, 15_000L), ATTEMPT_TIMEOUTS_MS.toList())
    }

    @Test
    fun ladderStaysUnderTheOuterDeadline() {
        // 4x3s + 15s = 27s of transport phases, under the 90s whole-call
        // deadline (NoveLA TOTAL_TIMEOUT_MS) even before jitter.
        assertTrue(ATTEMPT_TIMEOUTS_MS.sum() < 90_000L)
    }
}
