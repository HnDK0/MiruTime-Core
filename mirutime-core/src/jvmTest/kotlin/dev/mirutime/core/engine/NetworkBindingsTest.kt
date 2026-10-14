package dev.mirutime.core.engine

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.engine.binding.NetworkBindings
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import dev.mirutime.core.network.PluginHttpClient
import dev.mirutime.core.network.security.SsrfGuard
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NetworkBindingsTest {

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
        ssrfGuard: SsrfGuard? = null,
    ): PluginHttpClient = PluginHttpClient(
        metadata = metadata,
        systemInfo = FakeSystemInfo(),
        cookieStore = FakeCookieStore(),
        logger = RecordingLogger(),
        engine = engine,
        ssrfGuard = ssrfGuard,
    )

    private fun session(script: String, engine: MockEngine, ssrfGuard: SsrfGuard? = null): LuaSession {
        val s = createLuaSession("dev.mirutime.demo", script)
        NetworkBindings(client(engine, ssrfGuard), "dev.mirutime.demo").install(s)
        return s
    }

    @Test
    fun httpGetReturnsBodyStatusHeadersCharset() {
        val engine = MockEngine {
            respond(
                content = "hello",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/plain; charset=UTF-8"),
            )
        }
        val s = session(
            """
            function fetch()
                return http_get("https://example.com/page")
            end
            """.trimIndent(),
            engine,
        )

        val result = s.call("fetch", emptyList())
        s.dispose()

        assertEquals(null, result.error)
        val map = assertIs<LuaData.LuaMap>(result.data)
        assertEquals(LuaData.LuaBool(true), map.entries["success"])
        assertEquals(LuaData.LuaNum(200.0), map.entries["code"])
        assertEquals(LuaData.LuaNum(200.0), map.entries["status"])
        assertEquals(LuaData.LuaStr("hello"), map.entries["body"])
        assertEquals(LuaData.LuaStr("UTF-8"), map.entries["charset"])
        val headers = assertIs<LuaData.LuaMap>(map.entries["headers"])
        // NoveLA contract: each header maps to an ARRAY of values.
        val contentType = assertIs<LuaData.LuaList>(headers.entries["content-type"])
        assertEquals(listOf(LuaData.LuaStr("text/plain; charset=UTF-8")), contentType.items)
    }

    @Test
    fun httpGetNon2xxIsUnsuccessfulWithRealCode() {
        val engine = MockEngine {
            respond("not found", HttpStatusCode.NotFound)
        }
        val s = session(
            """
            function fetch()
                return http_get("https://example.com/missing")
            end
            """.trimIndent(),
            engine,
        )

        val result = s.call("fetch", emptyList())
        s.dispose()

        val map = assertIs<LuaData.LuaMap>(result.data)
        assertEquals(LuaData.LuaBool(false), map.entries["success"])
        assertEquals(LuaData.LuaNum(404.0), map.entries["code"])
        assertEquals(LuaData.LuaStr("not found"), map.entries["body"])
    }

    @Test
    fun httpPostSendsBodyAndSniffsJsonContentType() {
        var seenBody: String? = null
        var seenContentType: String? = null
        var seenMethod: String? = null
        val engine = MockEngine { request ->
            seenMethod = request.method.value
            val textBody = request.body as io.ktor.http.content.TextContent
            seenContentType = textBody.contentType.toString()
            seenBody = textBody.text
            respond("accepted")
        }
        val s = session(
            """
            function send()
                return http_post("https://example.com/api", '{"q":1}')
            end
            """.trimIndent(),
            engine,
        )

        val result = s.call("send", emptyList())
        s.dispose()

        val map = assertIs<LuaData.LuaMap>(result.data)
        assertEquals(LuaData.LuaBool(true), map.entries["success"])
        assertEquals("POST", seenMethod)
        assertTrue(seenContentType!!.startsWith("application/json"), "was $seenContentType")
        assertEquals("""{"q":1}""", seenBody)
    }

    @Test
    fun httpGetHeadMethodReturnsEmptyBody() {
        var seenMethod: String? = null
        val engine = MockEngine { request ->
            seenMethod = request.method.value
            respond("ignored")
        }
        val s = session(
            """
            function probe()
                return http_get("https://example.com/live", { method = "HEAD" })
            end
            """.trimIndent(),
            engine,
        )

        val result = s.call("probe", emptyList())
        s.dispose()

        val map = assertIs<LuaData.LuaMap>(result.data)
        assertEquals("HEAD", seenMethod)
        assertEquals(LuaData.LuaStr(""), map.entries["body"])
    }

    @Test
    fun httpGetUnsupportedMethodFailsBeforeNetwork() {
        var engineCalls = 0
        val engine = MockEngine {
            engineCalls++
            respond("ok")
        }
        val s = session(
            """
            function bad()
                return http_get("https://example.com/", { method = "DELETE" })
            end
            """.trimIndent(),
            engine,
        )

        val result = s.call("bad", emptyList())
        s.dispose()

        assertTrue(result.error != null, "unsupported method must surface as a script error")
        assertEquals(0, engineCalls)
    }

    @Test
    fun httpGetForwardsCustomHeaders() {
        var seenCustom: String? = null
        val engine = MockEngine { request ->
            seenCustom = request.headers["X-Custom"]
            respond("ok")
        }
        val s = session(
            """
            function fetch()
                return http_get("https://example.com/page", { headers = { ["X-Custom"] = "abc" } })
            end
            """.trimIndent(),
            engine,
        )

        val result = s.call("fetch", emptyList())
        s.dispose()

        assertEquals(null, result.error)
        assertEquals("abc", seenCustom)
    }

    @Test
    fun timeoutSurfacesAsTimeoutError() {
        val engine = MockEngine {
            delay(500)
            respond("late")
        }
        val s = session(
            """
            function fetch()
                return http_get("https://example.com/slow", { timeout = 50 })
            end
            """.trimIndent(),
            engine,
        )

        val result = s.call("fetch", emptyList())
        s.dispose()

        // error path: no LuaCallResult.error — http_get returns a LuaMap
        // { error, errorType } so Lua code can react without a thrown error.
        assertEquals(null, result.error)
        val map = assertIs<LuaData.LuaMap>(result.data)
        assertEquals(LuaData.LuaStr("Timeout"), map.entries["errorType"])
    }

    @Test
    fun httpPostHonoursTimeout() {
        val engine = MockEngine {
            delay(500)
            respond("late")
        }
        val s = session(
            """
            function send()
                return http_post("https://example.com/slow", "a=1", { timeout = 50 })
            end
            """.trimIndent(),
            engine,
        )

        val result = s.call("send", emptyList())
        s.dispose()

        assertEquals(null, result.error)
        val map = assertIs<LuaData.LuaMap>(result.data)
        assertEquals(LuaData.LuaStr("Timeout"), map.entries["errorType"])
    }

    @Test
    fun batchErrorSlotsCarryHeaders() {
        // NoveLA HttpGetBatchFunction.withHeadersFallback: every slot exposes
        // headers — error slots get an (empty) table, never a missing field.
        val engine = MockEngine { respond("must not be reached") }
        val s = session(
            """
            function fetchAll()
                local r = http_get_batch({
                    "https://blocked.example/a",
                    "https://blocked.example/b",
                })
                for i = 1, #r do
                    if r[i].success ~= false then return "bad-success-" .. i end
                    if type(r[i].headers) ~= "table" then return "bad-headers-" .. i end
                end
                return "ok"
            end
            """.trimIndent(),
            engine,
            ssrfGuard = SsrfGuard { listOf("10.0.0.1") },
        )

        val result = s.call("fetchAll", emptyList())
        s.dispose()

        assertEquals(null, result.error)
        assertEquals(LuaData.LuaStr("ok"), result.data)
    }

    @Test
    fun batchHonoursConcurrencyLimit() {
        val current = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val engine = MockEngine {
            val now = current.incrementAndGet()
            maxObserved.updateAndGet { prev -> maxOf(prev, now) }
            delay(80)
            current.decrementAndGet()
            respond("ok")
        }
        val s = session(
            """
            function fetchAll()
                return http_get_batch({
                    "https://example.com/1",
                    "https://example.com/2",
                    "https://example.com/3",
                    "https://example.com/4",
                    "https://example.com/5",
                    "https://example.com/6",
                }, { maxConcurrent = 2 })
            end
            """.trimIndent(),
            engine,
        )

        val result = s.call("fetchAll", emptyList())
        s.dispose()

        assertEquals(null, result.error)
        val list = assertIs<LuaData.LuaList>(result.data)
        assertEquals(6, list.items.size)
        for (item in list.items) {
            val map = assertIs<LuaData.LuaMap>(item)
            assertEquals(LuaData.LuaNum(200.0), map.entries["status"])
        }
        assertTrue(
            maxObserved.get() <= 2,
            "expected <=2 concurrent requests, saw ${maxObserved.get()}",
        )
        // And the limit is actually parallel, not serialized.
        assertTrue(maxObserved.get() >= 2, "expected parallel execution, saw ${maxObserved.get()}")
    }

    @Test
    fun sleepHonoursDelay() {
        val engine = MockEngine { respond("unused") }
        val s = session(
            """
            function nap()
                sleep(150)
                return 1
            end
            """.trimIndent(),
            engine,
        )

        var value: LuaData? = null
        val elapsed = measureTimeMillis {
            value = s.call("nap", emptyList()).data
        }
        s.dispose()

        assertEquals(LuaData.LuaNum(1.0), value)
        assertTrue(elapsed >= 150, "sleep returned too early: ${elapsed}ms")
        assertTrue(elapsed < 1500, "sleep took too long: ${elapsed}ms")
    }

    @Test
    fun ssrfBlockedSurfacesAsErrorType() {
        val engine = MockEngine { respond("must not be reached") }
        val s = session(
            """
            function fetch()
                return http_get("https://internal.example/")
            end
            """.trimIndent(),
            engine,
            ssrfGuard = SsrfGuard { listOf("10.0.0.1") },
        )

        val result = s.call("fetch", emptyList())
        s.dispose()

        // error path: LuaMap { error, errorType } as the data value.
        assertEquals(null, result.error)
        val map = assertIs<LuaData.LuaMap>(result.data)
        assertEquals(LuaData.LuaStr("SsrfBlocked"), map.entries["errorType"])
        val error = assertIs<LuaData.LuaStr>(map.entries["error"])
        assertTrue(error.value.isNotBlank())
    }
}
