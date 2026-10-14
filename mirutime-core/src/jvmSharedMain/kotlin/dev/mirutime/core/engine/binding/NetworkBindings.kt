package dev.mirutime.core.engine.binding

import dev.mirutime.core.engine.LuaSession
import dev.mirutime.core.network.PluginHttpClient
import dev.mirutime.core.network.cf.CfChallengeRequiredException
import dev.mirutime.core.network.pluginCharset
import dev.mirutime.core.network.security.DnsFailureException
import dev.mirutime.core.network.security.ResponseTooLargeException
import dev.mirutime.core.network.security.SsrfBlockedException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import java.nio.charset.Charset

// URL-keyed memo for http_get, ACTIVE only while LuaPluginSource.book()
// aggregates its per-field getters: each getter fetches the same book page,
// so a non-caching plugin would hit the site once per getter (~7x per
// book() without this). Owned/driven by LuaPluginSource (begin/end around
// the getter sequence, cleared in finally), shared with NetworkBindings,
// which consults it in http_get only. http_post / http_get_batch and every
// non-book() path (catalog, chapters, ...) never read or write it.
// ceiling: keyed by URL ONLY — the same URL requested with different headers
// (or method) within one book() shares the first response; acceptable because
// book() getters don't vary headers per page. Plugins with their own caches
// (e.g. a details cache) sit ABOVE this one and are unaffected. Tables are
// COPIED at the boundary (copy-on-store + copy-on-read): a plugin mutating
// r.body/r.headers of one getter's result must not poison the others —
// LuaValue scalars are immutable, only LuaTables alias.
// Session is single-threaded (facade contract), so no locking here.
class GetMemo {
    private var enabled = false
    private val responses = mutableMapOf<String, LuaValue>()

    fun begin() {
        responses.clear()
        enabled = true
    }

    fun end() {
        enabled = false
        responses.clear()
    }

    fun get(url: String): LuaValue? = if (enabled) responses[url]?.let { copyResponse(it) } else null

    fun put(url: String, response: LuaValue) {
        if (enabled) responses[url] = copyResponse(response)
    }

    // Deep copy of a response table: nested headers/body tables must not
    // alias the stored copy (LuaValue scalars are immutable, LuaTables alias).
    private fun copyResponse(value: LuaValue): LuaValue {
        if (!value.istable()) return value
        val src = value.checktable()
        val dst = LuaTable()
        for (key in src.keys()) dst.set(copyResponse(key), copyResponse(src.get(key)))
        return dst
    }
}

// Lua -> Ktor network bridge: installs http_get / http_post / http_get_batch /
// sleep into a session's Globals. PluginHttpClient is injected, never a global.
// Response shape matches NoveLA: { success, body, code, headers } on every
// completed exchange (success is 2xx), and { success=false, body=message,
// code=-1 } on a bridge failure. `status`/`charset`/`error`/`errorType` remain
// as aliases for existing host callers.
// ceiling: session worker is single-threaded; runBlocking here only blocks
// this session's worker thread; cancellation via interrupt. LuaJ functions
// are synchronous, so a suspend HTTP call has to be bridged with runBlocking
// — safe because LuaSession serializes all calls on one worker.
class NetworkBindings(
    private val client: PluginHttpClient,
    private val sourceId: String,
    // Disabled unless the factory wires the book()-driven memo (see GetMemo).
    private val getMemo: GetMemo = GetMemo(),
    // Plugin-declared response charset (the script's `charset` global via
    // PluginMetadata.charset, NoveLA SourceInterface.charset): the decoding
    // default when the request carries no explicit charset option. Null =
    // undeclared (previous behavior: Content-Type header, then UTF-8).
    private val defaultCharset: String? = null,
) {
    fun install(session: LuaSession) {
        session.globals.set("http_get", httpGet)
        session.globals.set("http_post", httpPost)
        session.globals.set("http_get_batch", httpGetBatch)
        session.globals.set("sleep", sleep)
    }

    private val httpGet = object : TwoArgFunction() {
        override fun call(url: LuaValue, opts: LuaValue): LuaValue {
            val target = url.checkjstring()
            val options = opts.takeIf { it.istable() }?.checktable()
            // Parse BEFORE the memo: an unsupported method must fail the same
            // way whether or not the URL is memoized.
            val request = parseOptions("http_get", options)
            // Memo hit (book() aggregation): the cached response table comes
            // back with no network call — and therefore no trace event; the
            // trace's lower request count is the memo evidence itself.
            getMemo.get(target)?.let { return it }
            val response = runBlocking { doRequest(target, request) }
            getMemo.put(target, response)
            return response
        }
    }

    private val httpPost = object : ThreeArgFunction() {
        override fun call(url: LuaValue, body: LuaValue, opts: LuaValue): LuaValue {
            val target = url.checkjstring()
            val payload = body.checkjstring()
            val options = opts.takeIf { it.istable() }?.checktable()
            val request = parseOptions("http_post", options)
            return runBlocking { doPost(target, payload, request) }
        }
    }

    private val httpGetBatch = object : TwoArgFunction() {
        override fun call(urls: LuaValue, opts: LuaValue): LuaValue {
            val urlTable = urls.checktable()
            val options = opts.takeIf { it.istable() }?.checktable()
            // Batch-wide defaults; every per-item key may override them.
            val global = parseOptions("http_get_batch", options)
            val maxConcurrent = options?.get("maxConcurrent")?.optint(4)?.coerceIn(1, 32) ?: 4
            val items = (1..urlTable.length()).map { i -> parseBatchItem(urlTable.get(i), global) }
            val results = runBlocking {
                val semaphore = Semaphore(maxConcurrent)
                coroutineScope {
                    items.map { item ->
                        async {
                            semaphore.withPermit { doRequest(item.url, item.options) }
                        }
                    }.awaitAll()
                }
            }
            return LuaValue.listOf(results.toTypedArray())
        }
    }

    private val sleep = object : TwoArgFunction() {
        override fun call(ms: LuaValue, opts: LuaValue): LuaValue {
            val millis = ms.checklong().coerceAtLeast(0)
            runBlocking { delay(millis) }
            return LuaValue.NIL
        }
    }

    // GET/HEAD; returns the NoveLA-shaped response table (or error table).
    private suspend fun doRequest(target: String, options: RequestOptions): LuaValue {
        return try {
            val response = client.get(
                sourceId = sourceId,
                url = target,
                headers = options.headers,
                timeoutMs = options.timeoutMs,
                followRedirects = options.followRedirects,
                method = options.method,
            )
            successMap(response, options)
        } catch (e: Exception) {
            errorMap(e)
        }
    }

    // POST; Content-Type comes from the plugin headers or is sniffed from the body.
    private suspend fun doPost(target: String, body: String, options: RequestOptions): LuaValue {
        return try {
            val contentType = options.headers.entries
                .firstOrNull { it.key.equals(HttpHeaders.ContentType, ignoreCase = true) }
                ?.value
                ?: detectContentType(body)
            val headers = options.headers.filterKeys { !it.equals(HttpHeaders.ContentType, ignoreCase = true) }
            val response = client.post(
                sourceId = sourceId,
                url = target,
                body = body,
                headers = headers,
                // Like http_get: the plugin's timeout option is the whole-call
                // budget (NoveLA callWithTimeout path).
                timeoutMs = options.timeoutMs,
                followRedirects = options.followRedirects,
                contentType = contentType,
            )
            successMap(response, options)
        } catch (e: Exception) {
            errorMap(e)
        }
    }

    private data class RequestOptions(
        val headers: Map<String, String> = emptyMap(),
        val charset: String? = null,
        val binary: Boolean = false,
        val method: String = "GET",
        val followRedirects: Boolean = true,
        val timeoutMs: Long? = null,
    )

    private data class BatchItem(val url: String, val options: RequestOptions)

    // Reads one config table, overlaying it on the batch-wide defaults.
    private fun parseOptions(tag: String, table: LuaTable?, base: RequestOptions = RequestOptions()): RequestOptions {
        if (table == null) return base
        val timeoutValue = table.get("timeout")
        val timeoutMs = if (timeoutValue.isnil()) {
            base.timeoutMs
        } else {
            timeoutValue.takeIf { it.isnumber() }?.tolong()?.takeIf { it > 0 }
        }
        return RequestOptions(
            headers = base.headers + parseHeaders(table),
            charset = table.get("charset").takeIf { it.isstring() }?.tojstring()?.takeIf { it.isNotBlank() }
                ?: base.charset,
            binary = table.get("binary").optboolean(base.binary),
            method = checkMethod(tag, table.get("method").takeIf { it.isstring() }?.tojstring() ?: base.method),
            followRedirects = table.get("followRedirects").optboolean(base.followRedirects),
            timeoutMs = timeoutMs,
        )
    }

    private fun parseBatchItem(value: LuaValue, base: RequestOptions): BatchItem {
        // type(): LuaJ reports isnumber()=true for numeric strings, and a bare
        // number must be rejected as an item rather than coerced to a URL.
        if (value.type() == LuaValue.TSTRING) return BatchItem(value.checkjstring(), base)
        if (!value.istable()) {
            throw IllegalArgumentException(
                "http_get_batch: item must be a URL string or a table with \"url\", got ${value.typename()}",
            )
        }
        val table = value.checktable()
        val url = table.get("url").optjstring(null)
            ?: throw IllegalArgumentException("http_get_batch: item table is missing the required \"url\" field")
        return BatchItem(url, parseOptions("http_get_batch", table, base))
    }

    // GET/HEAD only; anything else is a plugin bug and must fail before any network call.
    private fun checkMethod(fnName: String, raw: String): String {
        val method = raw.uppercase()
        require(method == "GET" || method == "HEAD") {
            "$fnName: unsupported method \"$raw\", allowed: GET, HEAD"
        }
        return method
    }

    private fun parseHeaders(options: LuaTable?): Map<String, String> {
        val table = options?.get("headers")?.takeIf { it.istable() }?.checktable() ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (key in table.keys()) {
            result[key.checkjstring()] = table.get(key).checkjstring()
        }
        return result
    }

    private suspend fun successMap(response: HttpResponse, options: RequestOptions): LuaValue {
        val status = response.status.value
        val bytes = response.bodyAsBytes()
        // Charset: explicit option wins, otherwise the plugin's declared
        // charset global, otherwise Content-Type, otherwise UTF-8.
        val charset = options.charset ?: pluginCharset(defaultCharset, response.headers[HttpHeaders.ContentType], null)

        val table = LuaTable()
        table.set("success", LuaValue.valueOf(status in 200..299))
        table.set("code", status)
        table.set("status", status) // host-facing alias
        table.set("charset", charset) // host-facing alias

        val headersTable = LuaTable()
        for (name in response.headers.names()) {
            val values = LuaTable()
            response.headers.getAll(name).orEmpty().forEachIndexed { i, value -> values.set(i + 1, value) }
            headersTable.set(name.lowercase(), values)
        }
        table.set("headers", headersTable)

        if (options.binary) {
            val bodyTable = LuaTable()
            bytes.forEachIndexed { i, byte -> bodyTable.set(i + 1, LuaValue.valueOf(byte.toInt() and 0xFF)) }
            table.set("body", bodyTable)
        } else {
            table.set("body", if (options.method == "HEAD") "" else decode(bytes, charset))
        }
        return table
    }

    private fun decode(bytes: ByteArray, charset: String): String =
        try {
            String(bytes, Charset.forName(charset))
        } catch (e: Exception) {
            bytes.decodeToString()
        }

    private fun errorMap(e: Exception): LuaValue {
        val message = e.message ?: e::class.simpleName ?: "request failed"
        val table = LuaTable()
        table.set("success", LuaValue.FALSE)
        table.set("body", message)
        table.set("code", -1)
        // NoveLA HttpGetBatchFunction.withHeadersFallback (LuaSourceLoader.kt:
        // 894-898): every batch slot carries headers, so error slots get an
        // empty table instead of a missing field.
        table.set("headers", LuaTable())
        table.set("error", message) // host-facing alias
        table.set("errorType", errorTypeOf(e)) // host-facing alias
        return table
    }

    private fun errorTypeOf(e: Exception): String = when (e) {
        is SsrfBlockedException -> "SsrfBlocked"
        is DnsFailureException -> "DnsFailure"
        is CfChallengeRequiredException -> "CfChallengeRequired"
        is ResponseTooLargeException -> "ResponseTooLarge"
        is HttpRequestTimeoutException, is TimeoutCancellationException -> "Timeout"
        else -> "Network"
    }

    private fun detectContentType(body: String): String =
        if (body.trimStart().firstOrNull() in listOf('{', '[')) "application/json"
        else "application/x-www-form-urlencoded"
}
