package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import dev.mirutime.core.network.cf.CfChallengeRequiredException
import dev.mirutime.core.network.cache.ResponseCache
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// PluginHttpClient's optional GET cache (NoveLA DiskCache parity). The cache
// itself is unit-tested in cache/ResponseCacheTest; these cover the WIRING:
// when the client reads, when it writes, and what must never be stored.
class PluginHttpClientCacheTest {

    private val metadata = PluginMetadata(
        id = "dev.mirutime.demo",
        name = "Demo",
        baseUrl = "https://example.com",
        contentType = ContentType.NOVEL,
        language = "ru",
        api = 1,
        version = "0.1.0",
        sha256 = "abc",
    )

    private fun client(
        engine: MockEngine,
        cache: ResponseCache?,
        clock: () -> Long = { 0L },
    ) = PluginHttpClient(
        metadata = metadata,
        systemInfo = FakeSystemInfo(),
        cookieStore = FakeCookieStore(),
        logger = RecordingLogger(),
        engine = engine,
        cache = cache,
        clock = clock,
    )

    // The client stamps storedAt from ITS clock and the cache validates TTL from
    // ITS OWN, so a host must hand both the same source — otherwise a frozen
    // client clock against the real-time default expires every entry at once.
    private fun cache(
        ttlMs: Long = 15 * 60 * 1000,
        clock: () -> Long = { 0L },
    ) = ResponseCache(ttlMs = ttlMs, clock = clock)

    private val url = "https://example.com/page"

    @Test
    fun cachedGetIsServedWithoutTouchingTheEngine() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("body-$calls")
        }
        val client = client(engine, cache())

        assertEquals("body-1", client.get("demo", url).bodyAsText())
        assertEquals("body-1", client.get("demo", url).bodyAsText())
        assertEquals(1, calls, "second GET must be a cache hit")
    }

    @Test
    fun forceNetworkBypassesTheCacheAndRefreshesIt() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("body-$calls")
        }
        val client = client(engine, cache())

        assertEquals("body-1", client.get("demo", url).bodyAsText())
        // NoveLA's "no-cache" flag: skip the cached copy, hit the origin, and
        // leave the refreshed body behind for the next reader.
        assertEquals("body-2", client.get("demo", url, forceNetwork = true).bodyAsText())
        assertEquals("body-2", client.get("demo", url).bodyAsText())
        assertEquals(2, calls)
    }

    @Test
    fun postIsNeverCached() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("post-$calls")
        }
        val client = client(engine, cache())

        assertEquals("post-1", client.post("demo", url, body = "a").bodyAsText())
        assertEquals("post-2", client.post("demo", url, body = "a").bodyAsText())
        assertEquals(2, calls, "a POST body must never be replayed from cache")
    }

    @Test
    fun expiredEntryIsNotServed() = runTest {
        var now = 0L
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("body-$calls")
        }
        val cache = cache(ttlMs = 100, clock = { now })
        val client = client(engine, cache, clock = { now })

        assertEquals("body-1", client.get("demo", url).bodyAsText())
        now = 50
        assertEquals("body-1", client.get("demo", url).bodyAsText())
        now = 200
        assertEquals("body-2", client.get("demo", url).bodyAsText())
        assertEquals(2, calls, "an expired entry must revalidate against the origin")
    }

    // A challenge is a failure, not a body: storing it would poison the cache
    // with the interstitial and hide every later successful solve.
    @Test
    fun cfChallengeIsNeverCached() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond("<html>Just a moment...<div id=\"challenge-running\"></div></html>", HttpStatusCode.Forbidden)
            } else {
                respond("clean body")
            }
        }
        val client = client(engine, cache())

        assertFailsWith<CfChallengeRequiredException> { client.get("demo", url) }
        assertEquals("clean body", client.get("demo", url).bodyAsText())
        assertEquals("clean body", client.get("demo", url).bodyAsText())
        assertEquals(2, calls, "only the clean 200 may be cached")
    }

    // Ambient fresh-network scope (driven by the adapter around
    // catalog/catalogFiltered, NoveLA withForceNetwork): same bypass+refresh
    // semantics as the explicit flag, without a per-call parameter.
    @Test
    fun ambientForceNetworkBypassesTheCacheAndRefreshesIt() = runTest {
        var calls = 0
        var forced = false
        val engine = MockEngine {
            calls++
            respond("body-$calls")
        }
        val ambient = PluginHttpClient(
            metadata = metadata,
            systemInfo = FakeSystemInfo(),
            cookieStore = FakeCookieStore(),
            logger = RecordingLogger(),
            engine = engine,
            cache = cache(),
            forceNetworkActive = { forced },
        )

        assertEquals("body-1", ambient.get("demo", url).bodyAsText())
        assertEquals("body-1", ambient.get("demo", url).bodyAsText())
        forced = true
        assertEquals("body-2", ambient.get("demo", url).bodyAsText())
        forced = false
        assertEquals("body-2", ambient.get("demo", url).bodyAsText())
        assertEquals(2, calls)
    }
}
