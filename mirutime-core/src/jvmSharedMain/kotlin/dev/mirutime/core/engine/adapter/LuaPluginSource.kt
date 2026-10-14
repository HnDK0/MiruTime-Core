package dev.mirutime.core.engine.adapter

import dev.mirutime.core.api.BookMeta
import dev.mirutime.core.api.CatalogItem
import dev.mirutime.core.api.CatalogPage
import dev.mirutime.core.api.Chapter
import dev.mirutime.core.api.ChapterContent
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginDescriptor
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.api.PluginSource
import dev.mirutime.core.api.PluginSourceFactory
import dev.mirutime.core.api.SubtitleTrack
import dev.mirutime.core.api.VideoStream
import dev.mirutime.core.api.getOrNull
import dev.mirutime.core.engine.LuaArg
import dev.mirutime.core.engine.LuaCallResult
import dev.mirutime.core.engine.LuaData
import dev.mirutime.core.engine.LuaSession
import dev.mirutime.core.engine.binding.CryptoBindings
import dev.mirutime.core.engine.binding.GetMemo
import dev.mirutime.core.engine.binding.HtmlBindings
import dev.mirutime.core.engine.binding.JsonBindings
import dev.mirutime.core.engine.binding.LogBindings
import dev.mirutime.core.engine.binding.MiscBindings
import dev.mirutime.core.engine.binding.NetworkBindings
import dev.mirutime.core.engine.binding.OsBindings
import dev.mirutime.core.engine.binding.PowSolver
import dev.mirutime.core.engine.binding.StorageBindings
import dev.mirutime.core.engine.binding.StringBindings
import dev.mirutime.core.engine.binding.UrlBindings
import dev.mirutime.core.engine.createSandboxGlobals
import dev.mirutime.core.engine.loader.LibraryLoader
import dev.mirutime.core.host.CfChallengeSolver
import dev.mirutime.core.host.CookieStore
import dev.mirutime.core.host.FileSystem
import dev.mirutime.core.host.KeyValueStore
import dev.mirutime.core.host.Logger
import dev.mirutime.core.host.SystemInfo
import dev.mirutime.core.installer.ScriptMetadataParser
import dev.mirutime.core.installer.toCfOptions
import dev.mirutime.core.network.HostOptionsRegistry
import dev.mirutime.core.network.NetworkTraceEvent
import dev.mirutime.core.network.PluginHttpClient
import dev.mirutime.core.network.PluginUserAgentRegistry
import dev.mirutime.core.network.UserAgentPresets
import dev.mirutime.core.network.cache.ResponseCache
import dev.mirutime.core.network.normalizeHost
import dev.mirutime.core.network.security.InetAddressHostResolver
import dev.mirutime.core.network.security.SsrfGuard
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Adapts a sandboxed LuaSession to the PluginSource contract.
//
// Lua-side API (NoveLA precedent): getCatalogList(index),
// getCatalogSearch(index, query), getCatalogFiltered(index, filters),
// per-field book getters (getBookTitle / getBookCoverImageUrl /
// getBookDescription / getBookGenres / getBookRating / getBookStatus /
// getBookLastUpdate, with getBookMeta as legacy fallback), arbitrary book
// extras via optional getBookExtras (MiruTime extension, absent in NoveLA),
// chapter list via
// parsePage(url, page) [preferred] or getChapterList(url), getChapterListHash,
// chapterContent dispatch over getVideoList(url) / getPageList(html, url) /
// getChapterText(html, url), getFilterList(), getSettingsSchema().
//
// Dispatch priority Video > Pages > Text matches the ethalon reader flow:
// video plugins declare getVideoList, image plugins declare getPageList and
// the reader prefers pages over the text path; none declared -> ScriptError.
// The adapter NEVER reorders chapter lists — plugins own the order.
// Session is single-threaded (facade contract): a mutex serializes calls
// arriving from concurrent coroutines (NoveLA precedent: adapter mutex).
class LuaPluginSource(
    private val session: LuaSession,
    override val metadata: PluginMetadata,
    // GET memo shared with NetworkBindings; book() drives begin/end so its
    // ~7 getters share one fetch of the same book page (see GetMemo).
    private val getMemo: GetMemo = GetMemo(),
    // Fresh-network scope shared with PluginHttpClient; catalog() and
    // catalogFiltered() drive force { } so their http_* skip the response
    // cache (NoveLA: luaEngine.withForceNetwork). Search/book/chapters do
    // not force (NoveLA doesn't force search).
    private val forceNetwork: ForceNetworkScope = ForceNetworkScope(),
) : PluginSource {

    private val mutex = Mutex()

    // NoveLA SourceInterface.referer (LuaSourceAdapter.kt:99
    // `override val referer: String = readReferer()`): the ready-made Referer
    // for hotlink-protected images, captured once at load — construction runs
    // on the factory thread right after the chunk, so this never reads the
    // live session off-mutex (a `by lazy` first read could race a locked()
    // call mutating globals on Dispatchers.IO).
    override val referer: String = readReferer()

    private fun readReferer(): String = try {
        session.globals.get("referer")
            .takeIf { it.isstring() }?.tojstring()?.trim().orEmpty()
    } catch (_: Exception) {
        ""
    }

    override suspend fun catalog(index: Int): Outcome<CatalogPage> = locked {
        // NoveLA LuaSourceAdapter.kt:257: getCatalogList runs inside
        // withForceNetwork — past the memory TTL cache and the disk cache.
        forceNetwork.force { toPage(callCatalog("getCatalogList", listOf(LuaArg.ArgNum(index.toDouble())))) }
    }

    override suspend fun search(index: Int, query: String): Outcome<CatalogPage> = locked {
        toPage(callCatalog("getCatalogSearch", listOf(LuaArg.ArgNum(index.toDouble()), LuaArg.ArgStr(query))))
    }

    override suspend fun catalogFiltered(
        index: Int,
        filters: Map<String, List<String>>,
    ): Outcome<CatalogPage> = locked {
        // NoveLA parity (LuaFilterSupport.toLuaTable): a select/sort/text/switch
        // selection reaches the plugin as a Lua STRING, checkbox/tristate/tag_input
        // as an array. Without this, `filters["sort"] or "latest"` yields a table
        // and the corpus idiom `.. filters["sort"] ..` raises "attempt to
        // concatenate table and string" — the app breaks the same way.
        val singleValueKeys = singleValueFilterKeys()
        val entries = filters.mapNotNull { (key, values) ->
            val baseKey = key.removeSuffix("_included").removeSuffix("_excluded")
            val isSingleValue = key in singleValueKeys || baseKey in singleValueKeys
            when {
                // NoveLA omits empty selections so `or "default"` keeps working.
                isSingleValue && values.isEmpty() -> null
                isSingleValue && values.size == 1 -> key to LuaArg.ArgStr(values.first())
                else -> key to LuaArg.ArgList(values.map { LuaArg.ArgStr(it) })
            }
        }
        val arg = LuaArg.ArgMap(entries.toMap())
        // NoveLA LuaSourceAdapter.kt:814,879: getCatalogFiltered runs inside
        // withForceNetwork, exactly like getCatalogList.
        forceNetwork.force { toPage(callCatalog("getCatalogFiltered", listOf(LuaArg.ArgNum(index.toDouble()), arg))) }
    }

    // NoveLA reads book details FIELD BY FIELD (LuaSourceAdapter.kt:292-428):
    // getBookTitle / getBookCoverImageUrl / getBookDescription / getBookGenres
    // / getBookRating / getBookStatus / getBookLastUpdate. The corpus defines
    // these (126/99/75/... files); getBookMeta exists nowhere in it and is kept
    // only as a legacy single-call fallback for old-style plugins.
    // Author: priority (a) getBookAuthor(url) if defined, (b) "author" key
    // from getBookMeta table, (c) null. MiruTime extension (absent in NoveLA).
    override suspend fun book(url: String): Outcome<BookMeta> = locked {
        // GET memo lives ONLY here: the getter sequence below shares one
        // fetch of the same book page. Cleared in finally — including error
        // paths — so a later book()/method never sees a stale entry.
        getMemo.begin()
        try {
            if (!hasFunction("getBookTitle") && hasFunction("getBookMeta")) {
                return@locked bookFromMeta(url)
            }
            val title = callScalarStr("getBookTitle", url)
            if (title is Outcome.Err) return@locked title
            val cover = callScalarStr("getBookCoverImageUrl", url)
            if (cover is Outcome.Err) return@locked cover
            val description = callScalarStr("getBookDescription", url)
            if (description is Outcome.Err) return@locked description
            val genres = callStrList("getBookGenres", url)
            if (genres is Outcome.Err) return@locked genres
            val rating = callScalarStrOrNum("getBookRating", url)
            if (rating is Outcome.Err) return@locked rating
            val status = callScalarStrOrNum("getBookStatus", url)
            if (status is Outcome.Err) return@locked status
            val lastUpdated = callScalarStrOrNum("getBookLastUpdate", url)
            if (lastUpdated is Outcome.Err) return@locked lastUpdated
            // Author priority: (a) getBookAuthor(url) if defined, (b) "author"
            // key from getBookMeta (legacy), (c) null. MiruTime extension.
            val author = callScalarStr("getBookAuthor", url).getOrNull()
            // Extras: only the optional getBookExtras extension on this path
            // (the legacy table has no per-field getters to merge with).
            val extras = callBookExtras(url)
            if (extras is Outcome.Err) return@locked extras
            Outcome.Ok(
                BookMeta(
                    title = title.getOrNull() ?: "",
                    coverUrl = cover.getOrNull(),
                    description = description.getOrNull(),
                    genres = genres.getOrNull() ?: emptyList(),
                    rating = rating.getOrNull(),
                    status = status.getOrNull(),
                    lastUpdated = lastUpdated.getOrNull(),
                    author = author,
                    extras = extras.getOrNull() ?: emptyMap(),
                ),
            )
        } finally {
            getMemo.end()
        }
    }

    // Legacy single-call path (old-style plugins that define getBookMeta).
    private fun bookFromMeta(url: String): Outcome<BookMeta> =
        when (val r = callOrErr("getBookMeta", listOf(LuaArg.ArgStr(url)))) {
            is Outcome.Err -> r
            is Outcome.Ok -> {
                val map = r.value as? LuaData.LuaMap ?: return scriptErr("getBookMeta returned non-table")
                val extras = callBookExtras(url)
                if (extras is Outcome.Err) return extras
                Outcome.Ok(
                    BookMeta(
                        title = map.str("title") ?: "",
                        coverUrl = map.str("cover"),
                        description = map.str("description"),
                        genres = map.strList("genres"),
                        rating = map.strOrNumString("rating"),
                        status = map.str("status"),
                        lastUpdated = map.strOrNumString("lastUpdated"),
                        author = map.str("author"),
                        extras = mergeExtras(map, extras.getOrNull() ?: emptyMap()),
                    ),
                )
            }
        }

    // Extras (BookMeta.extras): arbitrary plugin-declared metadata — tags,
    // alt titles, chapter count, translator, etc. Two sources:
    //   (a) legacy getBookMeta table keys NOT consumed as canonical fields —
    //       i.e. anything but the keys bookFromMeta reads: "title", "cover",
    //       "description", "genres", "rating", "status", "lastUpdated",
    //       "author" — stringified (scalars only);
    //   (b) the optional getBookExtras(url) table — a MiruTime extension
    //       (absent in NoveLA): every string/number entry, non-scalars
    //       skipped, not fatal.
    // Canonical getters always win: known keys are filtered from BOTH
    // sources, so extras never override a canonical field. Neither source
    // defined -> empty map (back-compat: plugins defining neither keep
    // working). A real Lua error() inside getBookExtras propagates, like
    // every other defined book getter.
    private fun callBookExtras(url: String): Outcome<Map<String, String>> =
        if (!hasFunction("getBookExtras")) {
            Outcome.Ok(emptyMap())
        } else {
            val result = session.call("getBookExtras", listOf(LuaArg.ArgStr(url)))
            result.error?.let { return Outcome.Err(it) }
            // nil/non-table means "nothing to report", not a failure (the
            // same optional-getter contract as rating/status).
            val table = result.data as? LuaData.LuaMap
                ?: return Outcome.Ok(emptyMap())
            val extras = mutableMapOf<String, String>()
            for ((key, value) in table.entries) {
                if (key in BOOK_META_KNOWN_KEYS) continue
                luaScalarStrOrNum(value)?.let { extras[key] = it }
            }
            Outcome.Ok(extras)
        }

    private fun mergeExtras(legacyTable: LuaData.LuaMap, extension: Map<String, String>): Map<String, String> {
        val extras = mutableMapOf<String, String>()
        for ((key, value) in legacyTable.entries) {
            if (key in BOOK_META_KNOWN_KEYS) continue
            luaScalarStrOrNum(value)?.let { extras[key] = it }
        }
        // getBookExtras (the dedicated extension) wins over stray legacy keys.
        extras.putAll(extension)
        return extras
    }

    // NoveLA prefers the paginated path: bookChaptersList tries parsePage
    // FIRST and falls back to getChapterList only when parsePage is absent
    // (DownloaderRepository.kt:390-427). Corpus: 17 plugins expose ONLY
    // parsePage, so this path is mandatory for parity. The adapter returns a
    // flat list, so it drains pages 1..totalPages, stopping early on a page
    // failure (NoveLA `break`), and never reorders.
    override suspend fun chapters(url: String): Outcome<List<Chapter>> = locked {
        if (hasFunction("parsePage")) chaptersViaParsePage(url) else chaptersViaList(url)
    }

    private fun chaptersViaList(url: String): Outcome<List<Chapter>> =
        when (val r = callOrErr("getChapterList", listOf(LuaArg.ArgStr(url)))) {
            is Outcome.Err -> r
            is Outcome.Ok -> {
                val list = r.value as? LuaData.LuaList ?: return scriptErr("getChapterList returned non-list")
                // Order is preserved verbatim — chronological is the plugin's job.
                Outcome.Ok(list.items.mapNotNull { toChapter(it) })
            }
        }

    private fun chaptersViaParsePage(url: String): Outcome<List<Chapter>> {
        val first = callOrErr("parsePage", listOf(LuaArg.ArgStr(url), LuaArg.ArgNum(1.0)))
        if (first is Outcome.Err) return first
        val firstData = first.getOrNull() ?: return scriptErr("parsePage returned non-table")
        val firstPage = parsePageResult(firstData) ?: return scriptErr("parsePage returned non-table")
        val chapters = firstPage.chapters.toMutableList()
        for (page in 2..firstPage.totalPages) {
            // NoveLA bookChaptersPage: a failed page returns null and the loop
            // stops early — keep the chapters already collected.
            val r = session.call("parsePage", listOf(LuaArg.ArgStr(url), LuaArg.ArgNum(page.toDouble())))
            if (r.error != null) break
            val data = r.data
            if (data == null || data is LuaData.LuaNil) break
            val next = parsePageResult(data) ?: break
            chapters += next.chapters
        }
        return Outcome.Ok(chapters)
    }

    private data class PagedChapters(val chapters: List<Chapter>, val totalPages: Int)

    private fun parsePageResult(data: LuaData): PagedChapters? {
        val map = data as? LuaData.LuaMap ?: return null
        val items = (map.entries["chapters"] as? LuaData.LuaList)?.items.orEmpty()
        // NoveLA optint(1): absent/non-number totalPages defaults to a single page.
        val totalPages = (map.entries["totalPages"] as? LuaData.LuaNum)?.value?.toInt() ?: 1
        return PagedChapters(items.mapNotNull { toChapter(it) }, totalPages)
    }

    private fun toChapter(item: LuaData): Chapter? {
        val t = item as? LuaData.LuaMap ?: return null
        return Chapter(
            title = t.str("title") ?: "",
            url = t.str("url") ?: "",
            volume = t.str("volume"),
            // NoveLA LuaSourceAdapter.kt:648: `uploaded =
            // table.get("uploaded").takeIf { it.isnumber() }?.tolong()` —
            // only a Lua number converts; date strings stay null.
            uploaded = (t.entries["uploaded"] as? LuaData.LuaNum)?.value?.toLong(),
        )
    }

    // NoveLA getChapterListHash (SourceInterface.kt:110): optional update-
    // detection hash. Absent function -> null (NoveLA: `if fn.isnil()
    // Success(null)`). NOTE: PluginSource has no such method yet, so this is a
    // concrete-class extension; add `override` once the interface carries it.
    suspend fun chapterListHash(url: String): Outcome<String?> = locked {
        if (!hasFunction("getChapterListHash")) return@locked Outcome.Ok(null)
        when (val r = callOrErr("getChapterListHash", listOf(LuaArg.ArgStr(url)))) {
            is Outcome.Err -> r
            is Outcome.Ok -> Outcome.Ok((r.value as? LuaData.LuaStr)?.value)
        }
    }

    override suspend fun chapterContent(html: String, url: String): Outcome<ChapterContent> =
        locked {
            when {
                hasFunction("getVideoList") ->
                    toVideo(callOrErr("getVideoList", listOf(LuaArg.ArgStr(url))))
                hasFunction("getPageList") ->
                    toPagesLenient(session.call("getPageList", listOf(LuaArg.ArgStr(html), LuaArg.ArgStr(url))))
                hasFunction("getChapterText") ->
                    toText(callOrErr("getChapterText", listOf(LuaArg.ArgStr(html), LuaArg.ArgStr(url))))
                else -> scriptErr("plugin declares none of getVideoList/getPageList/getChapterText")
            }
        }

    override suspend fun filters(): Outcome<List<Map<String, String>>> = locked {
        toFilterMaps(callOrErr("getFilterList", emptyList()))
    }

    override suspend fun settingsSchema(): Outcome<List<Map<String, String>>> = locked {
        toFilterMaps(callOrErr("getSettingsSchema", emptyList()))
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    // Ethalon precedent (withContext(Dispatchers.IO) around every call):
    // NetworkBindings bridges suspend HTTP via runBlocking inside Lua
    // functions — hop off the caller's dispatcher so a plugin's network I/O
    // never blocks the host's main thread (ANR).
    private suspend fun <T> locked(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }

    // Filter keys whose selected value is a bare string under the NoveLA
    // contract (LuaFilterSupport.toLuaTable: select/sort/text/switch -> string,
    // checkbox/tristate/tag_input -> array). Read from the plugin's own
    // getFilterList; a missing or failing list yields an empty set so every
    // value keeps the array shape (pre-parity behavior). Called from inside
    // catalogFiltered's locked block, so it must NOT take the mutex itself.
    private fun singleValueFilterKeys(): Set<String> {
        if (!hasFunction("getFilterList")) return emptySet()
        val maps = when (val result = toFilterMaps(callOrErr("getFilterList", emptyList()))) {
            is Outcome.Err -> return emptySet()
            is Outcome.Ok -> result.value
        }
        return maps.mapNotNullTo(mutableSetOf()) { filter ->
            val key = filter["key"] ?: return@mapNotNullTo null
            val type = filter["type"]
            if (type != null && type in SINGLE_VALUE_FILTER_TYPES) key else null
        }
    }

    private fun hasFunction(name: String): Boolean = session.globals.get(name).isfunction()

    private fun callOrErr(fn: String, args: List<LuaArg>): Outcome<LuaData> {
        val result = session.call(fn, args)
        result.error?.let { return Outcome.Err(it) }
        val data = result.data
        if (data == null || data is LuaData.LuaNil) return scriptErr("$fn returned nil")
        return Outcome.Ok(data)
    }

    private fun scriptErr(detail: String): Outcome.Err =
        Outcome.Err(PluginError.ScriptError(detail))

    // ── per-field book getters (NoveLA LuaSourceAdapter) ─────────────────────
    // Each getter takes the book URL and yields a scalar. An absent getter
    // yields the field default; a getter that exists and errors propagates the
    // error for the whole book() call (the host model aggregates into one
    // BookMeta, unlike NoveLA's per-field screen which shows them separately).

    private fun callScalarStr(fn: String, url: String): Outcome<String?> =
        if (!hasFunction(fn)) {
            Outcome.Ok(null)
        } else {
            when (val r = callOrErr(fn, listOf(LuaArg.ArgStr(url)))) {
                is Outcome.Err -> r
                is Outcome.Ok -> Outcome.Ok((r.value as? LuaData.LuaStr)?.value?.takeIf { it.isNotBlank() })
            }
        }

    private fun callScalarStrOrNum(fn: String, url: String): Outcome<String?> =
        if (!hasFunction(fn)) {
            Outcome.Ok(null)
        } else {
            val result = session.call(fn, listOf(LuaArg.ArgStr(url)))
            result.error?.let { return Outcome.Err(it) }
            // Optional fields (rating/status/lastUpdated): a nil return means
            // "absent", not a failure — luaScalarStrOrNum maps it to null, and
            // a real Lua error() still propagates via result.error above.
            Outcome.Ok(result.data?.let { luaScalarStrOrNum(it) })
        }

    private fun callStrList(fn: String, url: String): Outcome<List<String>> =
        if (!hasFunction(fn)) {
            Outcome.Ok(emptyList())
        } else {
            when (val r = callOrErr(fn, listOf(LuaArg.ArgStr(url)))) {
                is Outcome.Err -> r
                is Outcome.Ok -> Outcome.Ok(
                    (r.value as? LuaData.LuaList)?.items
                        ?.mapNotNull { (it as? LuaData.LuaStr)?.value }
                        ?: emptyList(),
                )
            }
        }

    // Ratings/timestamps/status come as string OR number (NoveLA optjstring:
    // a number stringifies, e.g. 4.6 -> "4.6"); nil/other -> null.
    private fun luaScalarStrOrNum(v: LuaData): String? = when (v) {
        is LuaData.LuaStr -> v.value.takeIf { it.isNotBlank() }
        is LuaData.LuaNum -> luaNumToString(v.value)
        else -> null
    }

    // LuaJ keeps the int/float distinction (a Lua `1712345678` literal is an
    // integer and stringifies without a decimal), but the session boundary
    // collapses every number to Double — re-apply that formatting so integer
    // timestamps/ratings don't come back as "1.712345678E9".
    private fun luaNumToString(v: Double): String =
        if (v.isFinite() && v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    // Ethalon precedent (convertLuaResultToPagedList): a non-table result is
    // an empty page, not an error.
    // Catalog/search paths follow the ethalon (convertLuaResultToPagedList):
    // nil AND non-table results are an EMPTY page, not an error — a plugin
    // returning a bare `return` for "nothing found" must not surface as a
    // failure. getPageList follows the same rule (toPagesLenient: nil AND
    // non-table are empty page lists, NoveLA LuaSourceAdapter.kt:544-555);
    // nil stays an error for chapters/video/text/filters.
    private fun callCatalog(fn: String, args: List<LuaArg>): Outcome<LuaData?> {
        val result = session.call(fn, args)
        result.error?.let { return Outcome.Err(it) }
        val data = result.data
        return Outcome.Ok(if (data is LuaData.LuaNil) null else data)
    }

    private fun toPage(result: Outcome<LuaData?>): Outcome<CatalogPage> = when (result) {
        is Outcome.Err -> result
        is Outcome.Ok -> {
            val map = result.value as? LuaData.LuaMap
            val items = (map?.entries?.get("items") as? LuaData.LuaList)?.items.orEmpty()
                .mapNotNull { item ->
                    val t = item as? LuaData.LuaMap ?: return@mapNotNull null
                    CatalogItem(
                        title = t.str("title") ?: "",
                        url = t.str("url") ?: "",
                        coverUrl = t.str("cover"),
                        rating = t.strOrNumString("rating"),
                        author = t.str("author"),
                    )
                }
            val hasNext = (map?.entries?.get("hasNext") as? LuaData.LuaBool)?.value ?: false
            Outcome.Ok(CatalogPage(items, hasNext))
        }
    }

    private fun toText(result: Outcome<LuaData>): Outcome<ChapterContent> = when (result) {
        is Outcome.Err -> result
        is Outcome.Ok -> {
            val text = result.value as? LuaData.LuaStr
                ?: return scriptErr("getChapterText returned non-string")
            Outcome.Ok(ChapterContent.Text(text.value))
        }
    }

    // NoveLA LuaSourceAdapter.kt:555 (`if (!result.istable())
    // return@withSourceContext emptyList()`): a non-table result is "no
    // pages", not a failure. Deliberate divergence from the catch at :544-550
    // (a Lua exception inside getPageList also yields emptyList there): a real
    // Lua error() propagates as Outcome.Err below, so a broken manga plugin
    // fails loudly instead of rendering an empty chapter. Only nil/non-table
    // DATA is lenient; a parked show_error still propagates via result.error.
    private fun toPagesLenient(result: LuaCallResult): Outcome<ChapterContent> {
        result.error?.let { return Outcome.Err(it) }
        val list = result.data as? LuaData.LuaList
            ?: return Outcome.Ok(ChapterContent.Pages(emptyList()))
        return Outcome.Ok(
            ChapterContent.Pages(list.items.mapNotNull { (it as? LuaData.LuaStr)?.value }),
        )
    }

    private fun toVideo(result: Outcome<LuaData>): Outcome<ChapterContent> = when (result) {
        is Outcome.Err -> result
        is Outcome.Ok -> {
            val list = result.value as? LuaData.LuaList
                ?: return scriptErr("getVideoList returned non-list")
            val streams = list.items.mapIndexedNotNull { index, item ->
                val t = item as? LuaData.LuaMap ?: return@mapIndexedNotNull null
                val streamUrl = t.str("url")
                if (streamUrl.isNullOrBlank()) return@mapIndexedNotNull null
                VideoStream(
                    url = streamUrl,
                    headers = t.mapOfStr("headers"),
                    mime = normalizeVideoMime(t.entries["mime"], index),
                    quality = t.str("quality"),
                    subtitles = (t.entries["subtitles"] as? LuaData.LuaList)?.items.orEmpty()
                        .mapNotNull { s ->
                            val st = s as? LuaData.LuaMap ?: return@mapNotNull null
                            val sUrl = st.str("url")
                            // Corpus + NoveLA use `lang` (LuaSourceAdapter.kt:726,
                            // es/veohentai.lua:470); keep `language` as fallback.
                            if (sUrl.isNullOrBlank()) {
                                null
                            } else {
                                SubtitleTrack(sUrl, st.str("lang") ?: st.str("language"), st.str("label"))
                            }
                        },
                )
            }
            Outcome.Ok(ChapterContent.Video(streams))
        }
    }

    // NoveLA LuaSourceAdapter.kt:668-695 (normalizeVideoMime): the optional
    // `mime` hint becomes the canonical mime media3 understands, else null —
    // an unrecognized non-empty mime would push the stream into a progressive
    // source with NO fallback to URL-extension sniffing (e.g.
    // `application/octet-stream` breaks `.m3u8`), while null keeps the
    // URI fallback. Non-string values are ignored, not fatal.
    private fun normalizeVideoMime(value: LuaData?, itemIndex: Int): String? {
        if (value == null || value is LuaData.LuaNil) return null
        if (value !is LuaData.LuaStr) return null
        val raw = value.value.trim()
        if (raw.isEmpty()) return null
        return when (val mime = raw.lowercase()) {
            "hls", "m3u8",
            "application/x-mpegurl",
            "application/vnd.apple.mpegurl" -> "application/x-mpegURL"
            "mp4", "video/mp4" -> "video/mp4"
            "mpd", "application/dash+xml" -> "application/dash+xml"
            else -> mime.takeIf {
                it.startsWith("video/") ||
                    it == "application/vnd.ms-sstr+xml" ||
                    it == "application/x-rtsp"
            }
        }
    }

    private fun toFilterMaps(result: Outcome<LuaData>): Outcome<List<Map<String, String>>> =
        when (result) {
            is Outcome.Err -> result
            is Outcome.Ok -> {
                val list = result.value as? LuaData.LuaList
                    ?: return scriptErr("filter list is not a list")
                Outcome.Ok(
                    list.items.mapNotNull { item ->
                        val t = item as? LuaData.LuaMap ?: return@mapNotNull null
                        buildMap {
                            for ((k, v) in t.entries) flattenInto(this, k, v)
                        }
                    },
                )
            }
        }

    // The host contract is List<Map<String,String>> and cannot hold nested
    // tables, so filter/setting fields are FLATTENED into dotted keys while
    // preserving the nested structure: scalar `type="select"` -> "type", the
    // `options` list of {value,label} -> "options.count" + "options.<i>.value"
    // / "options.<i>.label" (0-based), booleans -> "true"/"false" (NoveLA reads
    // defaultValue/defaultAscending/multiselect/allowCustom). This is the only
    // representation the fixed return type admits; a future typed Filter model
    // would replace it. NoveLA: parseLuaFilterList / parseLuaSettingsSchema.
    private fun flattenInto(target: MutableMap<String, String>, prefix: String, value: LuaData) {
        when (value) {
            is LuaData.LuaStr -> target[prefix] = value.value
            is LuaData.LuaNum -> target[prefix] = luaNumToString(value.value)
            is LuaData.LuaBool -> target[prefix] = value.value.toString()
            is LuaData.LuaList -> {
                target["$prefix.count"] = value.items.size.toString()
                value.items.forEachIndexed { i, item -> flattenInto(target, "$prefix.$i", item) }
            }
            is LuaData.LuaMap -> value.entries.forEach { (k, v) ->
                flattenInto(target, if (prefix.isEmpty()) k else "$prefix.$k", v)
            }
            is LuaData.LuaNil -> Unit
        }
    }

    // ── LuaData map helpers ──────────────────────────────────────────────────

    private fun LuaData.LuaMap.str(key: String): String? =
        (entries[key] as? LuaData.LuaStr)?.value

    // Ethalon precedent (convertLuaTableToBookResult): ratings/timestamps come
    // as string OR number; numbers stringify ("4.6" -> "4.6").
    private fun LuaData.LuaMap.strOrNumString(key: String): String? = when (val v = entries[key]) {
        is LuaData.LuaStr -> v.value.takeIf { it.isNotBlank() }
        is LuaData.LuaNum -> luaNumToString(v.value)
        else -> null
    }

    private fun LuaData.LuaMap.strList(key: String): List<String> =
        (entries[key] as? LuaData.LuaList)?.items?.mapNotNull { (it as? LuaData.LuaStr)?.value } ?: emptyList()

    private fun LuaData.LuaMap.mapOfStr(key: String): Map<String, String> =
        (entries[key] as? LuaData.LuaMap)?.entries?.entries
            ?.mapNotNull { (k, v) -> (v as? LuaData.LuaStr)?.let { k to it.value } }
            ?.toMap() ?: emptyMap()

    companion object {
        // Filter types NoveLA passes to plugins as a bare STRING, not an array
        // (LuaFilterSupport.toLuaTable). Everything else is an array.
        private val SINGLE_VALUE_FILTER_TYPES = setOf("select", "sort", "text", "switch")

        // The keys bookFromMeta reads as canonical BookMeta fields — filtered
        // out of both extras sources so extras never override a canonical field.
        private val BOOK_META_KNOWN_KEYS = setOf(
            "title", "cover", "description", "genres", "rating", "status", "lastUpdated", "author",
        )
    }
}

// Fresh-network scope for the catalog paths (NoveLA precedent:
// LuaEngine.withForceNetwork, a ThreadLocal flag read at http_get time —
// LuaSourceLoader.kt:85,93-95). One instance per plugin, shared by the
// adapter (drives force { } around catalog/catalogFiltered) and
// PluginHttpClient (reads isActive() when deciding to skip the response
// cache). Plain boolean like GetMemo: the adapter sets it around the
// synchronous session.call on the caller's thread and the client's http_*
// runs on that same call stack, so no locking is needed.
class ForceNetworkScope {
    private var active = false

    val isActive: Boolean get() = active

    suspend fun <T> force(block: suspend () -> T): T {
        active = true
        try {
            return block()
        } finally {
            active = false
        }
    }
}

// Factory seam for PluginRegistry (Task 14): owns the bindings-before-execute
// ordering — sandbox globals, ALL engine bindings (network, storage, html,
// crypto, json, string, url, pow) and require_lib are installed BEFORE the
// plugin chunk runs, so top-level require_lib/html_parse resolve (Task 13
// seam). The chunk executing at load time may throw, which the
// registry turns into PluginState.Failed.
class LuaPluginSourceFactory(
    private val systemInfo: SystemInfo,
    private val cookieStore: CookieStore,
    private val keyValueStore: KeyValueStore,
    private val logger: Logger,
    private val engine: HttpClientEngine,
    private val fileSystem: FileSystem,
    private val libDir: String = "lua_libs",
    private val hostOptions: HostOptionsRegistry? = null,
    // CF challenge solver passed straight through to PluginHttpClient; null
    // (JVM default) yields CfChallengeRequired instead of a silent success.
    private val solver: CfChallengeSolver? = null,
    // Guarded by default (audit constraint); pass a custom guard or null to
    // disable — tests inject null so fake hostnames skip real DNS.
    private val ssrfGuard: SsrfGuard? = SsrfGuard(InetAddressHostResolver()),
    // Diagnostic network observer (NetworkTraceEvent), passed straight through
    // to PluginHttpClient. null (default) is the app path; tooling such as
    // plugin-tester wires it to render a NETWORK TRACE section.
    private val trace: ((NetworkTraceEvent) -> Unit)? = null,
    // Optional response cache (NoveLA DiskCache parity), passed straight
    // through to PluginHttpClient. Owned by the host — null (default) is off.
    // The host MUST hand the cache the same clock source the client uses
    // (see PluginHttpClient's clock contract): mismatched epochs expire
    // every entry immediately (or never).
    private val cache: ResponseCache? = null,
) : PluginSourceFactory {
    private val libraryLoader = LibraryLoader(fileSystem, libDir)

    override fun create(descriptor: PluginDescriptor): PluginSource {
        val metadata = descriptor.metadata
        // Per-plugin fresh-network scope (item 12): catalog paths force it,
        // the client consults it when serving from the response cache.
        val forceNetwork = ForceNetworkScope()
        val client = PluginHttpClient(
            metadata,
            systemInfo,
            cookieStore,
            logger,
            engine,
            solver = solver,
            ssrfGuard = ssrfGuard,
            hostOptions = hostOptions,
            trace = trace,
            forceNetworkActive = forceNetwork::isActive,
            cache = cache,
        )
        val globals = createSandboxGlobals()
        // Session exists but the chunk has NOT run yet: bindings first.
        val session = LuaSession(metadata.id, globals)
        // One GET memo shared by the bindings (consulted in http_get) and the
        // source (drives begin/end around book()'s getter sequence).
        val getMemo = GetMemo()
        NetworkBindings(client, metadata.id, getMemo, defaultCharset = metadata.charset).install(session)
        StorageBindings(keyValueStore, cookieStore, metadata.id).install(session)
        // Ethalon NoveLA installs the html/hash/codec helpers into every
        // session (LuaSourceLoader) — real plugins call them at load time.
        HtmlBindings.install(session)
        CryptoBindings.install(session)
        JsonBindings.install(session)
        StringBindings.install(session)
        UrlBindings.install(session)
        PowSolver.install(session)
        // Diagnostic + misc globals NoveLA installs on every session:
        // log_info/log_error, os_time, detect_pagination, get_user_agent,
        // base64_decode (the string form; CryptoBindings provides the bytes one).
        LogBindings.install(session)
        OsBindings.install(session)
        MiscBindings.install(session)
        libraryLoader.install(session)
        globals.load(descriptor.script, "@${metadata.id}").call()
        registerLoadTimeOptions(session, metadata)
        return LuaPluginSource(session, metadata, getMemo, forceNetwork)
    }

    // Reads the script's own load-time globals from the LIVE session (the
    // chunk above already ran) and registers them per baseUrl host — NoveLA
    // LuaSourceAdapter.init { registerCfOptions(); registerUAPreset() } plus
    // readReferer. Runs even when the host-supplied metadata already carries
    // cfOptions (same script, same values, same owner — last write wins).
    // Skipped entirely when the factory was built without a registry.
    private fun registerLoadTimeOptions(session: LuaSession, metadata: PluginMetadata) {
        val registry = hostOptions ?: return
        if (metadata.baseUrl.isEmpty()) return
        val host = normalizeHost(metadata.baseUrl.substringAfter("://").substringBefore("/"))
        if (host.isEmpty()) return
        val owner = metadata.id
        val live = ScriptMetadataParser.parseGlobals(session.globals)
        // NoveLA LuaSourceAdapter.registerCfOptions (LuaSourceAdapter.kt:122-160):
        // cf_options table → CfDomainOptions per host.
        live.cfOptions?.toCfOptions()?.let { registry.setCfOptions(host, owner, it) }
        // NoveLA readReferer (LuaSourceAdapter.kt:193-196): per-host Referer
        // default for hotlink-protected covers.
        live.referer?.let { registry.setReferer(host, owner, it) }
        registerUserAgentPreset(session, metadata, host, owner, registry)
    }

    // NoveLA LuaSourceAdapter.kt:162-175 (registerUAPreset): calls the
    // plugin's getUserAgentPreset() once at load and registers the preset
    // NAME per source-id and per baseUrl host. A preset that is absent,
    // blank, errors, or returns a non-string is skipped — and a throw here
    // must never fail the install, so the whole read is guarded.
    private fun registerUserAgentPreset(
        session: LuaSession,
        metadata: PluginMetadata,
        host: String,
        owner: String,
        registry: HostOptionsRegistry,
    ) {
        try {
            if (!session.globals.get("getUserAgentPreset").isfunction()) return
            val preset = (session.call("getUserAgentPreset", emptyList()).data as? LuaData.LuaStr)
                ?.value?.trim()?.takeIf { it.isNotBlank() } ?: return
            PluginUserAgentRegistry.register(metadata.id, preset)
            PluginUserAgentRegistry.registerHost(host, preset)
            // The per-host request override carries the RESOLVED UA (an
            // unknown value is used literally — the same rule
            // PluginRequestBuilder.resolveUserAgent applies), never a bare
            // preset name.
            registry.setUserAgent(host, owner, UserAgentPresets.resolve(preset) ?: preset)
        } catch (_: Exception) {
            // A preset that errors at load is skipped, not fatal.
        }
    }
}
