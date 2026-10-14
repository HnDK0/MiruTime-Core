package dev.mirutime.core.network.security

import dev.mirutime.core.api.PluginError
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

// PluginError is a data hierarchy, not Throwable — this bridges the
// oversized-body failure out of suspend call sites
// (precedent: CfChallengeRequiredException, Task 6).
class ResponseTooLargeException(val error: PluginError.ResponseTooLarge) :
    IllegalStateException("Response too large: limit=${error.limit}")

// Streaming body limit: reads the channel up to maxBytes+1 and throws the
// moment the threshold is crossed — an oversized body is never drained fully.
class BodySizeLimiter(private val maxBytes: Long) {

    suspend fun readWithinLimit(channel: ByteReadChannel): ByteArray {
        val limit = if (maxBytes == Long.MAX_VALUE) Long.MAX_VALUE else maxBytes + 1
        val bytes = channel.readRemaining(limit).readByteArray()
        if (bytes.size.toLong() > maxBytes) {
            throw ResponseTooLargeException(PluginError.ResponseTooLarge(maxBytes))
        }
        return bytes
    }
}
