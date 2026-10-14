package dev.mirutime.core.network.security

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BodySizeLimiterTest {

    private val fiveMb = 5L * 1024 * 1024

    @Test
    fun sixMbBodyWithFiveMbLimitThrows() = runTest {
        val limiter = BodySizeLimiter(fiveMb)
        val body = ByteArray(6 * 1024 * 1024)

        val error = assertFailsWith<ResponseTooLargeException> {
            limiter.readWithinLimit(ByteReadChannel(body))
        }
        assertEquals(fiveMb, error.error.limit)
    }

    @Test
    fun bodyWithinLimitIsReturned() = runTest {
        val limiter = BodySizeLimiter(fiveMb)
        val body = ByteArray(1024) { it.toByte() }

        val bytes = limiter.readWithinLimit(ByteReadChannel(body))

        assertContentEquals(body, bytes)
    }

    @Test
    fun bodyExactlyAtLimitIsAllowed() = runTest {
        val limiter = BodySizeLimiter(1024)
        val body = ByteArray(1024)

        val bytes = limiter.readWithinLimit(ByteReadChannel(body))

        assertEquals(1024, bytes.size)
    }

    @Test
    fun bodyOneByteOverLimitThrows() = runTest {
        val limiter = BodySizeLimiter(1024)
        val body = ByteArray(1025)

        val error = assertFailsWith<ResponseTooLargeException> {
            limiter.readWithinLimit(ByteReadChannel(body))
        }
        assertEquals(1024, error.error.limit)
    }
}
