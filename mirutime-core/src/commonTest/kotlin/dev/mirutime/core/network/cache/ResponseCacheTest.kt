package dev.mirutime.core.network.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ResponseCacheTest {

    private var now = 0L

    private fun newCache(
        maxEntries: Int = 200,
        maxBytes: Long = 50 * 1024 * 1024,
        ttlMs: Long = 15 * 60 * 1000,
    ): ResponseCache = ResponseCache(maxEntries, maxBytes, ttlMs) { now }

    private fun key(
        url: String = "https://a.example/page",
        sourceId: String = "src",
        headers: Map<String, String> = emptyMap(),
    ): CacheKey = CacheKey.from("GET", url, "utf-8", sourceId, headers)

    private fun entry(bodySize: Int = 10): CachedResponse =
        CachedResponse(
            status = 200,
            headers = mapOf("content-type" to listOf("text/html")),
            body = ByteArray(bodySize),
            storedAt = now,
        )

    @Test
    fun ttlExpiryReturnsNull() {
        val cache = newCache(ttlMs = 100)
        cache.put(key(), entry())

        now = 99
        assertEquals(200, cache.get(key())?.status)

        now = 100
        assertNull(cache.get(key()))
    }

    @Test
    fun lruEvictsLeastRecentlyUsedOnMaxEntries() {
        val cache = newCache(maxEntries = 2)
        cache.put(key(url = "https://a/1"), entry())
        cache.put(key(url = "https://a/2"), entry())

        // Touch entry 1 so entry 2 becomes the LRU victim.
        cache.get(key(url = "https://a/1"))
        cache.put(key(url = "https://a/3"), entry())

        assertNull(cache.get(key(url = "https://a/2")))
        assertEquals(200, cache.get(key(url = "https://a/1"))?.status)
        assertEquals(200, cache.get(key(url = "https://a/3"))?.status)
    }

    @Test
    fun lruEvictsUntilWithinMaxBytes() {
        val cache = newCache(maxBytes = 100)
        cache.put(key(url = "https://a/1"), entry(bodySize = 60))
        cache.put(key(url = "https://a/2"), entry(bodySize = 60))

        // 120 > 100: LRU (entry 1) evicted, entry 2 kept — not a full clear.
        assertNull(cache.get(key(url = "https://a/1")))
        assertEquals(200, cache.get(key(url = "https://a/2"))?.status)
    }

    @Test
    fun invalidateBySourceRemovesOnlyThatSource() {
        val cache = newCache()
        cache.put(key(sourceId = "alpha"), entry())
        cache.put(key(sourceId = "beta"), entry())

        cache.invalidateBySource("alpha")

        assertNull(cache.get(key(sourceId = "alpha")))
        assertEquals(200, cache.get(key(sourceId = "beta"))?.status)
    }

    @Test
    fun differentHeaderSetsProduceDifferentKeys() {
        val plain = key(headers = mapOf("accept" to "text/html"))
        val withAuth = key(headers = mapOf("accept" to "text/html", "authorization" to "Bearer x"))
        assertNotEquals(plain.headersHash, withAuth.headersHash)

        val cache = newCache()
        cache.put(plain, entry())
        assertNull(cache.get(withAuth))
        assertEquals(200, cache.get(plain)?.status)
    }

    @Test
    fun sameHeadersProduceSameHash() {
        val a = key(headers = mapOf("accept" to "text/html", "x-flag" to "1"))
        val b = key(headers = mapOf("x-flag" to "1", "accept" to "text/html"))
        assertEquals(a.headersHash, b.headersHash)
        assertEquals(64, a.headersHash.length)
    }

    @Test
    fun oversizedBodyIsNotCached() {
        val cache = newCache(maxBytes = 100)
        cache.put(key(), entry(bodySize = 101))
        assertNull(cache.get(key()))
    }

    @Test
    fun sha256KnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc"),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }
}
