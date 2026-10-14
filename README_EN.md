> Русский: [README.md](./README.md)

# MiruTime-Core

MiruTime core: a KMP library for Lua plugins and networking. The library builds
standalone and is consumed as an **AAR artifact**: `mavenLocal()` in dev, JitPack
with a pinned version in releases (final scheme, see §4). It is consumed
identically by the reader app and the JVM plugin-debug tool — the tool lives in
a separate neighboring repository `../mirutime-plugin-tester` (see §6).

| Module | What it is | Included in APK |
|---|---|---|
| `:mirutime-core` | KMP library (`commonMain` + `jvmSharedMain` + `androidMain`/`jvmMain`), targets `android` and `jvm` | yes |

Root Gradle project: `rootProject.name = "MiruTime-Core"`,
`include(":mirutime-core")` (the tool is in the neighboring `../mirutime-plugin-tester`, §6).

---

## 1. What it is

`mirutime-core` is a Kotlin Multiplatform library containing all Lua-plugin and
scraper behavior: a sandboxed LuaJ engine, a Ktor HTTP stack with CF bypass,
SSRF protection, plugin installation with sha256 verification, local sources
(epub/fb2/cbz), and video-stream selection. UI, player (media3), and Compose
stay on the consumer side — the library only returns data (`Outcome<T>`).

Targets and versions (from `mirutime-core/build.gradle.kts` / `gradle/libs.versions.toml`):

| Parameter | Value |
|---|---|
| Targets | `android` (namespace `dev.mirutime.core`, compileSdk 37, minSdk 26, JVM target 17) and `jvm` (JVM target 17) |
| Plugins | `org.jetbrains.kotlin.multiplatform` 2.4.20, `com.android.kotlin.multiplatform.library` 9.4.0, `maven-publish` |
| Group/version | `dev.mirutime` / `0.1.0` (overridable via `-Pgroup=… -Pversion=…`; JitPack passes them through `jitpack.yml`) |
| Lua | LuaJ-JSE 3.0.1 (pure Java, lives in `jvmSharedMain` — shared by both JVM-compatible targets) |
| HTTP | Ktor 3.6.0; engine selected automatically: **OkHttp on Android, CIO on JVM** |
| HTML | Ksoup 0.2.6 (a jsoup port) |
| iOS | none (android + jvm only) |

Key layout detail: `androidMain` **cannot see** `jvmMain` in this KMP
configuration, so the entire plugin runtime (LuaJ engine, bindings,
`LuaPluginSource`, installer, book parsers) is factored out into an intermediate
source set `jvmSharedMain` that both `jvmMain` and `androidMain` depend on. That
is why the Android build can work with plugins, not just the JVM one.

---

## 2. Features (as implemented)

### 2.1 Content types and the PluginSource contract

`ContentType { NOVEL, MANGA, VIDEO }` + `SUPPORTED_PLUGIN_API = 1` (a plugin
with a different `api` is not loaded, fails visibly: `PluginError.UnsupportedApi`).

```kotlin
interface PluginSource {
    val metadata: PluginMetadata
    val referer: String // "" = undeclared; from the script's `referer` global (hotlink covers)
    suspend fun catalog(index: Int): Outcome<CatalogPage>
    suspend fun search(index: Int, query: String): Outcome<CatalogPage>
    suspend fun catalogFiltered(index: Int, filters: Map<String, List<String>>): Outcome<CatalogPage>
    suspend fun book(url: String): Outcome<BookMeta>
    suspend fun chapters(url: String): Outcome<List<Chapter>>
    suspend fun chapterContent(html: String, url: String): Outcome<ChapterContent>
    suspend fun filters(): Outcome<List<Map<String, String>>>
    suspend fun settingsSchema(): Outcome<List<Map<String, String>>>
    suspend fun transformChapterUrl(url: String): String // default — identity (NoveLA hook)
}
```

- `ChapterContent` is sealed: `Text(text)` / `Pages(pageUrls)` / `Video(streams)`.
- `VideoStream(url, headers, mime, quality, subtitles: List<SubtitleTrack>)`;
  `mime` is normalized against the NoveLA whitelist (`hls`/`m3u8` →
  `application/x-mpegURL`, `mp4` → `video/mp4`, `video/*` as-is,
  unknown → `null` with fallback by URL extension).
- `Chapter.uploaded: Long?` — epoch millis, Lua numbers only (date strings are
  discarded, NoveLA parity).
- A `getPageList` returning a non-table (or nil) yields an empty `Pages`, not an
  error (NoveLA parity).
- Errors are `Outcome.Ok/Err` + sealed `PluginError`
  (`UserMessage(title,message,authUrl)`, `Network`, `DnsFailure`,
  `CfChallengeRequired`, `ResponseTooLarge`, `UnsupportedApi`, `ScriptError`).
- `chapterContent` dispatch (NoveLA priority): `getVideoList` →
  `getPageList` → `getChapterText`; none declared → `ScriptError`.
- `LuaPluginSource` additionally exposes `chapterListHash(url)` (for
  incremental chapter-list updates) — an extension beyond `PluginSource`.

Management: `PluginRegistry(sourceFactory, hostOptions).install(descriptor) ->
PluginHandle` with `StateFlow<PluginState>` (`Loaded/Failed`), per-host
CF/UA/referer-option registration, and idempotent `dispose()` (removes only its
own entries).

### 2.2 Plugin Lua API (what the adapter calls)

`getCatalogList(index)`, `getCatalogSearch(index, query)`,
`getCatalogFiltered(index, filters)`, book metadata via the
`getBookTitle/getBookCoverImageUrl/getBookDescription/getBookGenres/getBookRating/
getBookStatus/getBookLastUpdate/getBookAuthor` fields (+ legacy `getBookMeta`
fallback, + the optional MiruTime extension `getBookExtras(url)` — arbitrary
book metadata in `BookMeta.extras`),
chapter list via `parsePage(url, page)` (preferred, paginated) or
`getChapterList(url)`, `getChapterListHash(url)`, content via
`getVideoList(url)` / `getPageList(html, url)` / `getChapterText(html, url)`,
`getFilterList()`, `getSettingsSchema()`.

Filters: `select/sort/text/switch` are passed to the plugin as a string, the rest
(checkbox/tristate/tag_input) as an array; an empty selection is omitted so the
idiomatic `filters["sort"] or "latest"` works in the plugin (parity with NoveLA
`LuaFilterSupport`).

Script globals read by the engine at load time (after chunk execution,
registered per baseUrl host, removed on `dispose()`): `referer` (default
`Referer` when the caller did not send one — hotlinked covers),
`getUserAgentPreset()` (preset name → per-source + per-host UA),
`cf_options` (`whitelist`/`ignore_markers`/`trigger_markers` → `CfOptions`),
`charset` (default response decoding, e.g. `GBK`).

### 2.3 Lua binding catalog

Installed **before** the plugin chunk executes (`require_lib` and `html_parse`
can be called at the top level).

| Group | Globals |
|---|---|
| Network (`NetworkBindings`) | `http_get`, `http_post`, `http_get_batch`, `sleep` |
| HTML (`HtmlBindings`, Ksoup) | `html_parse`, `html_select`, `html_select_first`, `html_text`, `html_attr`, `html_html`, `html_outerHtml`, `html_absUrl`, `html_remove` + node methods `get_text/get_html/attr/select/remove` |
| Regex/strings (`StringBindings`) | `regex_match`, `regex_replace`, `string_normalize`, `string_split`, `string_trim`, `string_starts_with`, `string_ends_with`, `string_clean`, `unescape_unicode` |
| JSON (`JsonBindings`) | `json_parse`, `json_stringify` |
| Crypto/codecs (`CryptoBindings`) | `sha256`, `sha1`, `md5`, `crc32`, `hmac_sha256`, `aes_decrypt`, `aes_ctr`, `aes_gcm_decrypt`, `rsa_decrypt`, `ecdsa_sign_p256`, `rc4`, `pbkdf2`, `base64_encode`, `base64_decode_bytes`, `inflate`, `unpack_packed`, `json_encode` |
| Storage/host (`StorageBindings`) | `get_cookies`, `set_cookies`, `get_localStorage`, `set_localStorage`, `get_preference`, `set_preference`, `show_error` |
| Utilities | `log_info`, `log_error` (`LogBindings`), `os_time` (`OsBindings`), `detect_pagination`, `get_user_agent`, `base64_decode` (`MiscBindings`), `pow_solve` (`PowSolver`), `url_encode`, `url_encode_charset`, `url_resolve` (`UrlBindings`), `require_lib` (`LibraryLoader`) |

Semantics (NoveLA parity): `json_parse` of a broken string → `nil`,
`json_encode(nil)` → error; `regex_match` → 1-based array of full
matches, empty table on no match; `aes_decrypt(b64, key, iv)`,
`aes_gcm_decrypt(key, iv, ct, tag)`.

HTTP response format in Lua (NoveLA parity): `{ success, code, status, charset,
headers, body }`; bridge error — `{ success=false, code=-1, body, error,
errorType }` with `errorType ∈ SsrfBlocked | DnsFailure | CfChallengeRequired |
ResponseTooLarge | Timeout | Network`. `http_get_batch` — `maxConcurrent`
1..32 (default 4), `binary=true` returns the body as a byte array.

**Sandbox** (`Sandbox.kt`): `luajava`, `io`, `load`, `loadfile`,
`loadstring`, `dofile`, `require`, `package`, `debug` are removed; `os` is cut
down to `time`/`date`/`clock`/`difftime` (NoveLA parity, `coroutine` is
available). `LuaValue`/`LuaTable` never escape — the boundary uses
`LuaArg`/`LuaData`. Sessions are single-threaded: the adapter serializes calls
with a mutex and hops to `Dispatchers.IO` (the network bridge inside LuaJ uses
`runBlocking`).

`require_lib(id [, sha])`: process-wide memo of compiled libs (key —
content-sha256, lib change invalidates the cache, max 64 entries; the optional
`sha` is verified), each call executes a fresh table in the calling session's
globals (`__index` points at its own session). Mutable lib state is not shared
between plugins (a deliberate deviation from NoveLA for thread safety).

**Plugin multilinguality**: `metadata.language` (default `en`, read from the
script global), `Accept-Language` is taken from `SystemInfo.acceptLanguage`
on every request, the `index.yaml` index may be per-language (`sources:` in the
language file, `libraries:` in the root one), UA presets (`UserAgentPresets` +
aliases). UI localization itself is the host's job.

### 2.4 Networking

`PluginHttpClient` (code shared by all plugins; one client instance per plugin):

- default headers: full Chrome set (`sec-ch-ua` with GREASE, `sec-fetch-*`,
  `upgrade-insecure-requests`, `priority`) — only with a Chromium UA, `Accept`
  per request type, `Referer = scheme://host/`, `Accept-Encoding` from the same
  list as decoding; caller headers always win (`PluginRequestBuilder`);
- manual cookie bridge: host `CookieStore` → `Cookie` on the request, `Set-Cookie`
  back (the `ktor-client-cookies` plugin is not used);
- **decompression** gzip/deflate/`br` (`ktor-client-encoding`, pure-Java
  `org.brotli:dec` with no JNI);
- **manual redirects** (`RedirectFollower`), each hop re-checked by the
  SSRF guard; plus JS/meta navigations (`JsRedirectResolver`: `window.location*`,
  `location.replace/assign`, meta-refresh) on HTML GETs — with the page's
  `Referer`, SSRF checks, and a shared hop limit; `followRedirects=false`
  disables those too;
- **retries**: `429/502/503/504`, GET only, up to 4 retries, backoff from 500 ms
  (exponential), budget calls (`timeoutMs`) — a single attempt with no sleep;
  CF challenges are not burned through retries;
- **transport ladder** (NoveLA `NetworkClient` parity): up to 5 GET attempts with
  phase timeouts `3/3/3/3/15 s` and 0–400 ms jitter, sleep clipped to the
  remaining deadline; budget calls — one attempt; ceiling: pool `evictAll` before
  retry (NoveLA) is not ported — the Ktor engine abstraction has no pool handle,
  and a dead connection is not reused anyway;
- **catalog always hits the network**: `catalog()`/`catalogFiltered()` bypass
  the cache (but refresh it), `search()` does not (NoveLA `withForceNetwork`
  parity on list/filtered);
- **deadline**: 90 s per call (monotonic clock, checks at boundaries, not a
  coroutine timer), 10 s connect/socket timeout;
- **body limit**: 25 MB (`BodySizeLimiter`) → `PluginError.ResponseTooLarge`;
- **response cache** `ResponseCache` (optional): 200-entry / 50 MB / 15-min TTL
  LRU, SHA-256 key (method|url|charset|sourceId|headers); the host owns the
  cache, `null` = disabled (default);
- charset: plugin option → `Content-Type` → UTF-8 (`CharsetResolver`).
  The plugin option is the script's `charset` global (`PluginMetadata.charset`).

### 2.5 Security

- `SsrfGuard`: allow-list of `http/https` schemes only, blocks `localhost`/`*.localhost`,
  IPv4 literals, and **checks resolved addresses** via an injectable
  `HostResolver` (on Android/JVM — `InetAddressHostResolver`), fail-closed for
  ambiguous literals; verdict `Ok/Blocked/DnsFailure`. Ceiling (recorded in
  code): DNS-rebinding/TOCTOU between check and connect is not covered —
  there is no IP pinning.
- Guard enabled by default in `LuaPluginSourceFactory`
  (`ssrfGuard = SsrfGuard(InetAddressHostResolver())`), disabled explicitly by
  passing `null` or a custom guard.
- Lua sandbox (see §2.3), path-safe `id` check in the installer
  (`[A-Za-z0-9_.-]+`, `..` forbidden; single dots are legal: `mvlempyr.com`),
  `require_lib` — `[a-z0-9_]+`.
  Deliberately stricter than NoveLA (`isValidExtensionId` there only cuts `/`,
  `\`, `..`): the whole corpus (93 index ids + all script `id`s, including
  `mvlempyr.com`, `bad-fixture`, `NovelArrow`) passes our pattern, and a wider
  one buys nothing — only path-traversal surface.
- sha256 verification at install and script-load time, atomic commit
  (tmp + rename) — a broken install never touches the already installed file.

### 2.6 Cloudflare

- `CloudflareDetector`: `403/429/503` statuses + body markers (`__cf_chl_`,
  turnstile, ddos-guard, etc. — the bare phrase `Just a moment` is deliberately
  excluded: it occurs in book texts), custom `whitelist`/`ignore_markers`/
  `trigger_markers` from the script's `cf_options` global; static assets by
  extension (`STATIC_EXTENSIONS` from NoveLA) are never a challenge; built-in
  host whitelist (`github.com`, `raw.githubusercontent.com`, `images.weserv.nl`,
  `wsrv.nl`); hard IP-ban URL markers (`/cdn-cgi/error/`, `error=1020/1015`)
  — challenge without corroboration;
- `CfGate`: per-host lock (30 s to acquire) + cooldown — 120 s after success,
  60 s terminal after failure (NoveLA values: `COOLDOWN_MS`,
  `TERMINAL_COOLDOWN_MS`); a repeat call during cooldown fails fast with
  `CfChallengeRequired` instead of re-running the solver. A repeat solve clears
  ONLY `cf_clearance` (`CookieStore.clearCookies(host, names)`, NoveLA
  `clearCookiesForDomain`), other cookies survive;
- `CfChallengeSolver` — host interface (`solve(CfChallenge): CfSolution?`):
  in prod it is a WebView/handler on the app side, in the dev tool an external
  process (see §6). The host must validate clearance with a live request
  (NoveLA `probeIsClear` pattern: the cookie alone proves nothing) —
  there is no WebView in core, the probe is host code. After solving — request
  retry; if the challenge persists — a second solve (max 2 per request, NoveLA
  `MAX_MANUAL_ATTEMPTS` parity); obtained cookies go to the `CookieStore`,
  the browser's effective UA goes to `HostOptionsRegistry`.
- `PluginMetadata.cfOptions`/`userAgentPreset` are registered in
  `HostOptionsRegistry` at `install` and removed at `dispose()`.

### 2.7 Cookie / UA persistence

- The host implements `CookieStore` (`get/setFromResponse/clear/clearCookies`)
  and `KeyValueStore` (`get/set/remove` by `namespace=key`, namespace =
  `sourceId`) — persistence is entirely on its side (file/DB/SharedPreferences),
  including `cf_clearance` survival across restarts (otherwise — a fresh bypass
  on every launch; core persists nothing itself — the KMP core does not own the
  Android CookieManager).
- UA resolution: `PluginMetadata.userAgentPreset` → `PluginUserAgentRegistry`
  (by sourceId, then by host walking parent domains) → `UserAgentPresets.DEFAULT`
  (full Chromium UA). Presets: Chrome/Safari/Firefox/Edge/Samsung, desktop and
  mobile, + aliases (`"Chrome Mobile"` etc.). A request never leaves with an
  empty or non-browser UA.

### 2.8 Plugin installation and updates

- `parsePluginIndex(yaml)` (snakeyaml, safe constructor): `sources:` + `libraries:`,
  fields `id/name/version/url/sha256/api`.
- `PluginInstaller(fileSystem, fetcher, installDir="lua_plugins", libDir="lua_libs")`:
  `id` validation → api gate → download → sha256 → syntax check in a clean
  sandbox (no execution) → header metadata parsing → atomic commit;
  `installLibrary/installLibraries` puts libs under `libDir` for `require_lib`.
- `isUpdateAvailable(entry)` — comparison by sha256 only (versions play no part).
- `ScriptLoader`: sha check, atomic write, `.lbc` bytecode cache
  (`LUAC_TAG = "3.0.1"` format tag — a LuaJ version change invalidates the cache).
- `ScriptMetadataParser`: reads the script header (`id/name/version/baseUrl/language/
  content_type/icon/description/charset/referer/cf_options`), `content_type`
  is validated (`novel|manga|video`), `language` is normalized (`mtl/multi` →
  `mtl`, otherwise lowercase/trim; NoveLA parity without `LanguageCode`), default `en`.
  `toPluginMetadata` passes `icon/description/charset/cf_options` through to
  `PluginMetadata` losslessly.

### 2.9 Local sources and video

- `LocalSource(fileSystem, libraryDir, contentType)` — a `PluginSource` over a
  file directory; `EpubParser`, `Fb2Parser`, `CbzParser` (+ `ZipArchive`,
  `LocalXml`) parsers in `jvmSharedMain`. Ceiling: a book is re-parsed on every call.
- `video/StreamResolver`: `pickStream(streams, preferQuality)` (exact quality
  match, otherwise the first element — reference semantics) and `hasMediaExt(url)`
  (`.m3u8/.mp4/.mkv`).

### 2.10 Request tracing

`NetworkTraceEvent(method, url, status, requestHeaders, responseHeaders,
bodyBytes, durationMs, cfBlocked, redirectHop)` + `trace` callback in
`LuaPluginSourceFactory`/`PluginHttpClient`. One emit per attempt **after** the
body is read (bodyBytes is the real size). Default `null` — the app path: no
field is computed, no CF detection runs for the trace. The consumer
(plugin-tester) renders the `NETWORK TRACE` section from it.

### 2.11 Host contracts (implemented by the consumer)

| Interface | Purpose |
|---|---|
| `FileSystem` | `read/write/exists/delete/rename/list` for scripts, libs, plugins |
| `KeyValueStore` | `get/set/remove(namespace, key)` — plugin settings and localStorage |
| `CookieStore` | `get(url)/setFromResponse(url, setCookieHeaders)/clear(host)/clearCookies(host, names)` |
| `Logger` | `d/w/e(tag, msg, t?)` |
| `SystemInfo` | `defaultUserAgent`, `acceptLanguage` |
| `CfChallengeSolver` | optional CF bypass (`solve(CfChallenge): CfSolution?`) |

---

## 3. Source sets: what lives where

```
mirutime-core/src
├── commonMain      # api/ (PluginSource, PluginRegistry, Types, Outcome, PluginError),
│                   # network/ (PluginHttpClient, RequestBuilder, RedirectFollower,
│                   #   retry, deadline, cache, cf/, security/, trace), host/,
│                   # video/, engine/LuaEngineFacade (expect, no LuaJ types)
├── jvmSharedMain   # engine/ (LuaJ engine, Sandbox, all binding/, adapter/LuaPluginSource,
│                   #   loader/), installer/ (Index/Plugin/ScriptMetadata), local/ (epub/fb2/cbz),
│                   #   CharsetResolver, BrotliEncoder, InetAddressHostResolver
├── androidMain     # createEngine() = OkHttp, currentTimeMillis() actual   → into AAR
├── jvmMain         # createEngine() = CIO,    currentTimeMillis() actual   → JVM only
├── commonTest      # tests (not published)
└── jvmTest         # 430 tests / 41 suites (not published)
```

---

## 4. Integration: final scheme (AAR artifact)

**Decided:** both the reader app and the `mirutime-plugin-tester` tool (separate
repo) consume the library the same way —
as an **AAR artifact**: from `mavenLocal()` in dev, from JitPack with a pinned
version in releases (alternative — GitHub Release artifacts). Composite build
(option (a)) remains an auxiliary tool for live-editing the library sources,
module copy (option (c)) is a last resort, not recommended.

| Scenario | Repository | Coordinates |
|---|---|---|
| dev | `mavenLocal()` (`publishToMavenLocal` before building the reader/tool) | `dev.mirutime:mirutime-core:0.1.0` |
| release | JitPack (or GitHub Release artifacts), version pinned explicitly | `com.github.<owner>:mirutime-core:<tag>` |

### (a) Composite build: `includeBuild("../MiruTime-Core")` — for source edits only

```kotlin
// consumer's settings.gradle.kts
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
    // + mavenLocal()/jitpack — if using option (b)
}
includeBuild("../MiruTime-Core")

// consumer's build.gradle.kts
dependencies {
    implementation("dev.mirutime:mirutime-core:0.1.0") // version — arbitrary, replaced by substitution
}
```

Gradle substitutes the `:mirutime-core` project for the `dev.mirutime:mirutime-core`
module (`group` + module name `mirutime-core` match). The consumer build then
compiles the library from sources — library edits are visible immediately.

> `implementation(project(":mirutime-core"))` directly across different git repos
> **does not work**: `project(...)` only sees modules of its own build. Either
> `includeBuild` + coordinates (see above), or a module copy inside the repo
> (option (c)), where `include(":mirutime-core")` makes `project()` available.

**What comes along** (runtime classpath, android variant):
Ktor 3.6.0 (`ktor-client-core` + engine `ktor-client-okhttp` + `ktor-http/io/utils/
events/websockets/http-cio/network/sse/serialization/encoding`), OkHttp 5.5.0,
`kotlinx-coroutines-core`, `kotlinx-serialization-json`, Ksoup 0.2.6 (+ `fleeksoft.io`,
`fleeksoft.charset`, `stately`), okio 3.18.2, LuaJ 3.0.1, snakeyaml 2.7,
`org.brotli:dec` 0.1.2. On the consumer's **compile** classpath there are
additionally three `api` dependencies of the library: `kotlinx-coroutines-core`,
`ktor-client-core`, `kotlinx-serialization-json` — their versions may be
overridden by the app's own versions (Gradle conflict resolution).

### (b) AAR artifact — the primary scheme

**Dev** (needs Android SDK):

```bash
# from this repo (Android SDK required for android publication)
ANDROID_HOME=~/Library/Android/sdk ./gradlew :mirutime-core:publishToMavenLocal
```

```kotlin
// consumer's settings.gradle.kts — same for the reader and mirutime-plugin-tester
dependencyResolutionManagement { repositories { mavenLocal(); google(); mavenCentral() } }
// build.gradle.kts
dependencies { implementation("dev.mirutime:mirutime-core:0.1.0") }
```

Three artifacts are actually published (verified via `publishToMavenLocal`):

| Coordinates | File | Purpose |
|---|---|---|
| `dev.mirutime:mirutime-core:0.1.0` | 826 B jar | root KMP publication (metadata, tooling-metadata) |
| `dev.mirutime:mirutime-core-android:0.1.0` | `.aar` 487 162 B | Android variant, selected automatically |
| `dev.mirutime:mirutime-core-jvm:0.1.0` | 523 412 B jar | JVM variant, selected automatically |

Publication tasks: `publishToMavenLocal` (all), or individually
`publishAndroidPublicationToMavenLocal`, `publishJvmPublicationToMavenLocal`,
`publishKotlinMultiplatformPublicationToMavenLocal`. Artifact selection by the
consumer happens via variant matching — no need to specify `-android`/`-jvm`
manually.

**Release.** The version is pinned explicitly (`…:mirutime-core:<tag>`) — JitPack
or GitHub Release artifacts, no `+`/`latest`:

JitPack: `jitpack.yml` runs
`./gradlew :mirutime-core:publishToMavenLocal -Pgroup=$GROUP -Pversion=$VERSION`
(JDK 17), i.e. artifacts are served as `com.github.<owner>:mirutime-core:<tag>`
(module name `mirutime-core`, not the repo name). The consumer adds
`maven("https://jitpack.io")` to `dependencyResolutionManagement` and pins a tag.
A live run through jitpack.io was not performed in this session.

**GitHub Action on tags** — a real `.github/workflows/release.yml` file
(not a snippet): on push of a `v*` tag it runs `jvmTest`, builds the AAR
(`assembleAndroidMain`) and the JVM jar (`jvmJar`) with `-Pversion` from the tag,
and creates a GitHub Release with both artifacts itself
(`softprops/action-gh-release`). JitPack then builds the tag on the consumer's
first request.

The reader and the `mirutime-plugin-tester` tool consume it uniformly: for JitPack —
`implementation("com.github.<owner>:mirutime-core:<tag>")` (+ the
`maven("https://jitpack.io")` repository); for GitHub Release artifacts — the
downloaded AAR goes into your own maven repository (or GitHub Packages) with its
URL specified. Only the repository and version change; the dependency itself is
the same; the version is pinned to a tag in both cases.

**What comes along** — the same list as in (a); only the source of the library
code differs.

**What does NOT need declaring in the app:**

- **Do not declare OkHttp directly** — it arrives transitively via
  `ktor-client-okhttp` (the library client's engine). A direct
  `implementation("com.squareup.okhttp3:okhttp:…")` entry is only needed by a UI
  layer that creates its own OkHttp clients — `coil-network-okhttp`,
  `media3-datasource-okhttp`; Gradle converges everything to a single copy
  anyway (conflict resolution), the second dependency adds nothing.
- **Ktor/OkHttp/Ksoup/okio/coroutines/kotlinx-serialization** — already in the
  "what comes along" list above, they do not need separate `dependencies`
  entries in the app.

**R8 / consumer rules:** the AAR ships its R8 rules (`proguard.txt` inside the
AAR, see `mirutime-core/consumer-rules.pro`) — keeps for LuaJ (`org.luaj.**`)
and snakeyaml (`org.yaml.snakeyaml.**`), nothing else. The rules are picked up
automatically, no need to duplicate them in the app's `proguard-rules.pro`.
**R8 full mode** is recommended for the app (AGP 8+ default) — that is the exact
configuration the rules were written for.

### (c) Module copy as a source set — last resort, not recommended

Per the user's decision the primary scheme is the AAR (§4 (b)); copying the
module is acceptable only if the library can be neither published nor connected
via `includeBuild`. What to copy in that case:

1. the whole `mirutime-core/` folder (`build.gradle.kts` + `src/` +
   `consumer-rules.pro`);
2. entries from `gradle/libs.versions.toml` (or hardcode the coordinates in the
   module's `build.gradle.kts`): plugins `org.jetbrains.kotlin.multiplatform`,
   `com.android.kotlin.multiplatform.library`, and libraries
   `kotlinx-coroutines-core`, `ktor-client-core`, `ktor-client-encoding`,
   `ktor-client-okhttp`, `ktor-client-cio`, `kotlinx-serialization-json`,
   `ksoup`, `okio`, `luaj-jse`, `snakeyaml`, `brotli-dec`;
3. in the consumer's `settings.gradle.kts`: `include(":mirutime-core")` and the
   `google()` + `mavenCentral()` repositories;
4. in the consumer's root `build.gradle.kts`: the same two plugins with `apply false`
   (as in this repo).

No need to copy `plugin-tester` (dev-only), nor `maven-publish`
(no publishing needed). Downside: library updates must be pulled in manually.
Upside: `project(":mirutime-core")` works as an ordinary module.
`Build and tests` — see §7; the android target needs the Android SDK
(compileSdk 37, minSdk 26), `local.properties` is in `.gitignore`.

---

## 5. APK size

### 5.1 "What goes where" table

| Piece | Android AAR | JVM jar | `mirutime-plugin-tester` | Consumer APK |
|---|---|---|---|---|
| `mirutime-core/commonMain` | ✅ | ✅ | ✅ (via artifact) | ✅ |
| `mirutime-core/jvmSharedMain` (LuaJ engine, bindings, installer, book parsers) | ✅ | ✅ | ✅ | ✅ |
| `mirutime-core/androidMain` (OkHttp engine) | ✅ | ❌ | ❌ | ✅ |
| `mirutime-core/jvmMain` (CIO engine) | ❌ | ✅ | ❌ | ❌ |
| `mirutime-core/consumer-rules.pro` (R8 keep for LuaJ/snakeyaml) | ✅ (in AAR as `proguard.txt`) | ❌ | ❌ | ✅ (rules applied by the app's R8) |
| `commonTest` / `jvmTest` (430 tests) | ❌ | ❌ | ❌ | ❌ |
| `mirutime-plugin-tester` (classes, jar) | ❌ | ❌ | ✅ (own build) | **❌ 0 bytes** |
| `mirutime-plugin-tester/cf_solve.py`, `.cf_cookies.json` | ❌ | ❌ | files next to the working directory | **❌ 0 bytes** |
| python3 / DrissionPage / Brave | ❌ | ❌ | external dev-machine process | **❌ 0 bytes** |
| `gradle/libs.versions.toml`, `jitpack.yml` | ❌ | ❌ | ❌ | ❌ (build-time only) |

### 5.2 Numbers (measured in this session, pre-R8)

Library's own code:

| Artifact | Size |
|---|---|
| `mirutime-core-android-0.1.0.aar` | 487 162 B (476 KB), containing `classes.jar` 523 237 B / 274 classes + `proguard.txt` 3 908 B |
| `mirutime-core-jvm-0.1.0.jar` | 523 412 B (511 KB) |
| root KMP jar | 826 B |

Runtime classpath (sum of all resolved artifacts, **pre-**shrink —
command: `./gradlew :mirutime-core:dependencies --configuration androidRuntimeClasspath`):

| Group | Android | Comment |
|---|---|---|
| Ktor + OkHttp engine | **3.56 MB** | `ktor-client-core` 940 KB, `okhttp.aar` 924 KB, `ktor-http` 448 KB, `ktor-utils` 388 KB, `ktor-io` 296 KB, `ktor-network` 197 KB, `ktor-websockets` 188 KB, `ktor-http-cio` 131 KB, rest < 100 KB |
| `kotlin-stdlib` 2.4.20 | 1.77 MB | |
| `kotlinx-coroutines-core` | 1.51 MB | pinned to 1.9.0 in `libs.versions.toml`, but Ktor 3.6.0 raises it to **1.11.0** |
| Ksoup + `fleeksoft.io` + `charset` + `stately` | 0.68 MB | |
| `kotlinx-serialization` core+json | 0.67 MB | |
| okio | 0.37 MB | |
| LuaJ 3.0.1 | 0.34 MB | |
| snakeyaml 2.7 | 0.32 MB | only needed by `IndexParser` |
| kotlinx-io (Ktor transitive) | 0.17 MB | |
| slf4j/annotations/androidx.startup+tracing | 0.18 MB | OkHttp/Ktor transitive |
| `org.brotli:dec` | 0.09 MB | |
| **Total Android** | **≈ 9.65 MB** | JVM variant: **≈ 8.99 MB** (CIO 135 KB + `ktor-network-tls` 194 KB instead of OkHttp) |

These are unpacked jar/aar sizes pre-R8: the final APK gets classes after
tree-shaking and compression, i.e. substantially less. The exact figure only
comes from a minified build of the app itself — no APK is built in this repo.

### 5.3 What actually shrinks the APK

1. **Consumer-side R8/minify is the main lever.** The AAR ships its consumer
   rules (`proguard.txt` inside the artifact, 3 908 B, source —
   `mirutime-core/consumer-rules.pro`): two keeps, `org.luaj.**` and
   `org.yaml.snakeyaml.**` — both libraries resolve classes/methods by name via
   reflection and ship no rules of their own. The rules are picked up
   automatically, no need to duplicate them in the app's `proguard-rules.pro`.
   Flip side: snakeyaml (332 KB) is now **not** stripped by R8 — it is pinned in
   full (ceiling and narrowing path in the `consumer-rules.pro` comments).
   Everything else — Ksoup, okio, brotli, unused Ktor engines/codecs — is freely
   stripped. The app's own `proguard-rules.pro` needs entries only for the app's
   own reflection. Whether it "works/doesn't work with R8 full mode" was not
   verified here — verify on a real minified reader build.
2. **The engine is already split by target.** The Android variant pulls only
   OkHttp, JVM only CIO; there is no `ktor-client-cio` in the Android
   classpath, nothing to exclude manually. No need to add a separate
   `ktor-client-*` dependency.
3. **`implementation`, not `api`, for "heavy" internals.** Ksoup, okio, LuaJ,
   snakeyaml, brotli-dec, `ktor-client-encoding` are `implementation`: they do
   not leak onto the consumer's compile classpath, available only via Gradle
   module metadata at runtime. `api` is only coroutines, `ktor-client-core`,
   serialization-json (the API types won't compile without them).
4. **Tests and tool don't ship.** `commonTest`/`jvmTest` are not published,
   the `plugin-tester` tool is factored out into the separate
   `../mirutime-plugin-tester` repository (0 bytes, verified via the AAR contents).
5. **Non-removable pieces — stated honestly:**
    - *video bindings cannot be cut*: the `getVideoList/getPageList/
      getChapterText` dispatcher and `StreamResolver` live in `jvmSharedMain`
      and are always in the artifact. Their cost is single KBs, nothing to save
      there.
    - *CF, SSRF, trace, cache* are likewise not build flags: same-source-set
      code. Trace with `trace = null` simply computes nothing (zero runtime
      overhead), the rest is a minimal contribution to total size.
    - *snakeyaml/luaj as separate "feature flags"* cannot be disabled — the
      dependency is declared in the module's `build.gradle.kts`, and R8 no
      longer strips them either: the AAR consumer rules keep both packages in
      full (~680 KB combined, ceiling and narrowing path in the
      `consumer-rules.pro` comments).
6. **No extra weight in the API surface**: new dependencies are not added to the
   library without need (project rule), so the §4 list is a closed set.

---

## 6. Running plugins (`mirutime-plugin-tester`)

The dev tool is **factored out of this repo** into the separate project
`/Users/mac/Documents/GitHub/mirutime-plugin-tester` — a plain JVM console
(`application`, `mainClass = dev.mirutime.tester.MainKt`) using **the same**
library and HTTP stack as the app (there is no second engine or second client
in it), consuming it as the published artifact
`com.github.HnDK0.MiruTime-Core:mirutime-core-jvm:<version>`: in dev —
`main-SNAPSHOT` from `mavenLocal()` (after `publishToMavenLocal` with the same
`-Pversion`), in release — pinned to a tag; the version is set once in the
tester's `gradle/libs.versions.toml` (§4b). Output format,
sections, and exit codes are described below — the tool itself did not change
when factored out, only how it connects to the library.

```bash
# 1) once (and after every lib edit) — publish the lib locally:
#    from THIS repo, Android SDK required
ANDROID_HOME=~/Library/Android/sdk ./gradlew :mirutime-core:publishToMavenLocal

# 2) minimal — from the neighboring repo
cd /Users/mac/Documents/GitHub/mirutime-plugin-tester
./gradlew --console=plain -q run --args="path/to/plugin.lua"

# with URLs (otherwise takes the first from the catalog/chapter list)
./gradlew --console=plain -q run --args="plugin.lua --url https://site.test/book/123 --chapter-url https://site.test/book/123/4"
```

Arguments:

| Argument | Meaning |
|---|---|
| `<plugin.lua>` | required, path to the script |
| `--url <url>` | book URL for detail tests (otherwise auto-picked from `catalog[1].url`) |
| `--chapter-url <url>` | chapter URL (otherwise auto-picked from `chapter_list[1].url`) |
| `--auth` | authenticated mode (CF bypass + cookies) |
| `--cf` | no-op: CF bypass is always automatic (cookies + on-demand solver) |

Environment variables: `LUA_LIBS_DIR` — directory for `require_lib` (otherwise
the tool copies `*.lua` from `../external-sources/libs` or
`../../external-sources/libs` into `build/lua_libs`), `CF_SOLVE_SCRIPT` — path
to the CF script.

**CF solver (dev machine only, not part of prod):** `mirutime-plugin-tester/cf_solve.py`
runs as `python3 <script> <url> <timeout>`, requires `DrissionPage` and the
Brave browser (path hardcoded: `/Applications/Brave Browser.app/Contents/MacOS/Brave`,
i.e. currently a macOS script). Cookies are saved to
`mirutime-plugin-tester/.cf_cookies.json` together with the UA; on the next run
the UA is replayed (cf_clearance is only valid with the same UA). The solver is
invoked at most **twice per host per run** (NoveLA parity); without the
script/python the tool degrades to `CfChallengeRequired` and keeps working. None
of these files ends up in the library or the APK.

**Output format:**

1. banner + `Plugin Info` (name/version/baseUrl/language/content type/load time);
2. `Functions defined:` / `Functions missing:` (12 canonical entry points +
   extended set: `getBookGenres`, `getBookLastUpdate`, `getBookChapterCount`,
   `getBookExtras`, `parsePage`, `getVideoList`);
3. blocks per test: `── <name> [PASS|FAIL] (<ms>) ──` + `✗ errors` +
   `· key: value`;
4. summary: `N/M passed · ALL PASSED | K FAILED · <ms> total`;
5. `NETWORK TRACE — <n> requests`: per attempt the method, URL, status
   (`✓/↗/✗`, `⚠ CF-BLOCKED`), body size, duration, `hop`, sent
   headers (Cookie truncated to 80 chars) and some response ones
   (`set-cookie`, `location`, `content-type`).

Test sections: `metadata`, `catalog`, `auto_book_url`, `search`, `pagination`,
`book_title`, `book_cover`, `book_description`, `book_status`, `book_rating`,
`book_extras`, `chapter_list`, `auto_chapter_url`, then by contentType —
`chapter_text` (novel) / `page_list` (manga) / `video_streams` (video), plus
`filters` (if `getFilterList` is declared), `plugin_events`, `site_health`.
The presence of `video_streams`/`page_list` is determined by the script's
`content_type`.

**Exit codes:**

| Code | When |
|---|---|
| `0` | all tests passed |
| `1` | no arguments (prints usage), file not found, `LOAD FAILED` (chunk load error), or any `FAIL` test |

A non-zero code fails the Gradle task: `./gradlew --console=plain -q run`
(in `../mirutime-plugin-tester`) ends with `BUILD FAILED` and
`finished with non-zero exit value 1` (verified on a
dummy script: `2/4 passed · 2 FAILED` → code 1).

---

## 7. Build and verification

```bash
./gradlew :mirutime-core:jvmTest                                        # 430 tests, 41 suites
ANDROID_HOME=~/Library/Android/sdk ./gradlew :mirutime-core:compileAndroidMain
ANDROID_HOME=~/Library/Android/sdk ./gradlew :mirutime-core:publishToMavenLocal
# tool (separate ../mirutime-plugin-tester repo): publishToMavenLocal above first
cd ../mirutime-plugin-tester && ./gradlew --console=plain -q run --args="plugin.lua"
```

Android tasks fail with `SDK location not found` if neither `ANDROID_HOME`
nor `sdk.dir` in `local.properties` is set (file in `.gitignore`).

---

## 8. Status and open decisions

- Released `v0.1.2` (GitHub Release with AAR + JVM jar; JitPack serves by tag).
  Tests: 430 / 41 suites green (`./gradlew :mirutime-core:jvmTest`).
- Key decisions (NoveLA parity, verified against a 96-plugin corpus):
  `regex_match` — 1-based array; `require_lib` — process-wide prototype memo,
  per-session tables; `json_parse` of broken JSON — `nil`; `BookMeta.extras` +
  `getBookExtras`; GET memo inside `book()`; CF — up to 2 solves, referer page
  for `/api/`, clearing only `cf_clearance`, the `Just a moment` marker removed
  deliberately.
- Not verified: the consumer's first live request to JitPack; R8 full mode on a
  real reader build (consumer rules are in the AAR — §4/§5.3); iOS target.

---

## 9. License

GPL-3.0-or-later © 2026 HnDK0, see LICENSE.
