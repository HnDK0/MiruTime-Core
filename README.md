# MiruTime-Core

Ядро MiruTime: KMP-библиотека Lua-плагинов и сети. Библиотека собирается
standalone и подключается **AAR-артефактом**: `mavenLocal()` в деве, JitPack с
пином версии в релизах (финальная схема, см. §4). Подключается одинаково и
ридер-приложению, и JVM-тулу для отладки плагинов — он живёт в отдельном
соседнем репозитории `../mirutime-plugin-tester` (см. §6).

| Модуль | Что это | Попадает в APK |
|---|---|---|
| `:mirutime-core` | KMP-библиотека (`commonMain` + `jvmSharedMain` + `androidMain`/`jvmMain`), таргеты `android` и `jvm` | да |

Корневой Gradle-проект: `rootProject.name = "MiruTime-Core"`,
`include(":mirutime-core")`. Модуль `:plugin-tester` из этого репо **вынесен**:
отдельный репозиторий `../mirutime-plugin-tester`, потребляет либу
опубликованным артефактом (§4b, §6).

---

## 1. Что это

`mirutime-core` — Kotlin Multiplatform библиотека, содержащая всё поведение
Lua-плагинов и скраперов: движок LuaJ в песочнице, Ktor HTTP-стек с CF-обходом,
SSRF-защиту, установку плагинов с sha256-верификацией, локальные источники
(epub/fb2/cbz) и выбор видеопотоков. UI, плеер (media3) и Compose остаются на
стороне потребителя — библиотека отдаёт только данные (`Outcome<T>`).

Таргеты и версии (из `mirutime-core/build.gradle.kts` / `gradle/libs.versions.toml`):

| Параметр | Значение |
|---|---|
| Таргеты | `android` (namespace `dev.mirutime.core`, compileSdk 37, minSdk 26, JVM target 17) и `jvm` (JVM target 17) |
| Плагины | `org.jetbrains.kotlin.multiplatform` 2.4.20, `com.android.kotlin.multiplatform.library` 9.4.0, `maven-publish` |
| Группа/версия | `dev.mirutime` / `0.1.0` (переопределяются `-Pgroup=… -Pversion=…`; JitPack передаёт их через `jitpack.yml`) |
| Lua | LuaJ-JSE 3.0.1 (чистый Java, живёт в `jvmSharedMain` — общий для обоих JVM-совместимых таргетов) |
| HTTP | Ktor 3.6.0; engine выбирается автоматически: **OkHttp на Android, CIO на JVM** |
| HTML | Ksoup 0.2.6 (порт jsoup) |
| iOS | нет (только android + jvm) |

Ключевая особенность раскладки: `androidMain` **не видит** `jvmMain` в этой KMP-
конфигурации, поэтому весь рантайм плагинов (LuaJ-движок, биндинги,
`LuaPluginSource`, инсталлер, парсеры книг) вынесен в промежуточный source set
`jvmSharedMain`, от которого зависят и `jvmMain`, и `androidMain`. Именно поэтому
Android-сборка умеет работать с плагинами, а не только JVM.

---

## 2. Возможности (по коду)

### 2.1 Контент-тайпы и контракт PluginSource

`ContentType { NOVEL, MANGA, VIDEO }` + `SUPPORTED_PLUGIN_API = 1` (плагин с
другим `api` не грузится, падает видимо: `PluginError.UnsupportedApi`).

```kotlin
interface PluginSource {
    val metadata: PluginMetadata
    val referer: String // "" = undeclared; из глобала `referer` скрипта (хотлинк-каверы)
    suspend fun catalog(index: Int): Outcome<CatalogPage>
    suspend fun search(index: Int, query: String): Outcome<CatalogPage>
    suspend fun catalogFiltered(index: Int, filters: Map<String, List<String>>): Outcome<CatalogPage>
    suspend fun book(url: String): Outcome<BookMeta>
    suspend fun chapters(url: String): Outcome<List<Chapter>>
    suspend fun chapterContent(html: String, url: String): Outcome<ChapterContent>
    suspend fun filters(): Outcome<List<Map<String, String>>>
    suspend fun settingsSchema(): Outcome<List<Map<String, String>>>
    suspend fun transformChapterUrl(url: String): String // дефолт — identity (хук NoveLA)
}
```

- `ChapterContent` — sealed: `Text(text)` / `Pages(pageUrls)` / `Video(streams)`.
- `VideoStream(url, headers, mime, quality, subtitles: List<SubtitleTrack>)`;
  `mime` нормализуется по белому списку NoveLA (`hls`/`m3u8` →
  `application/x-mpegURL`, `mp4` → `video/mp4`, `video/*` как есть,
  неизвестное → `null` с фолбэком по расширению URL).
- `Chapter.uploaded: Long?` — epoch millis, только Lua-числа (строки с датами
  отбрасываются, паритет NoveLA).
- `getPageList`, вернувший не-таблицу (или nil), — пустой `Pages`, не ошибка
  (паритет NoveLA).
- Ошибки — `Outcome.Ok/Err` + sealed `PluginError`
  (`UserMessage(title,message,authUrl)`, `Network`, `DnsFailure`,
  `CfChallengeRequired`, `ResponseTooLarge`, `UnsupportedApi`, `ScriptError`).
- Диспетчизация `chapterContent` (приоритет из NoveLA): `getVideoList` →
  `getPageList` → `getChapterText`; ни одна не объявлена → `ScriptError`.
- `LuaPluginSource` дополнительно отдаёт `chapterListHash(url)` (для
  инкрементального обновления списка глав) — это расширение сверх `PluginSource`.

Менеджмент: `PluginRegistry(sourceFactory, hostOptions).install(descriptor) ->
PluginHandle` с `StateFlow<PluginState>` (`Loaded/Failed`), регистрацией
CF/UA/referer-опций по хосту и идемпотентным `dispose()` (снимает только свои записи).

### 2.2 Lua API плагина (что вызывает адаптер)

`getCatalogList(index)`, `getCatalogSearch(index, query)`,
`getCatalogFiltered(index, filters)`, метаданные книги по полям
`getBookTitle/getBookCoverImageUrl/getBookDescription/getBookGenres/getBookRating/
getBookStatus/getBookLastUpdate/getBookAuthor` (+ legacy-фолбэк `getBookMeta`,
+ опциональное расширение MiruTime `getBookExtras(url)` — произвольные
метаданные книги в `BookMeta.extras`),
список глав `parsePage(url, page)` (предпочтительно, пагинация) либо
`getChapterList(url)`, `getChapterListHash(url)`, контент
`getVideoList(url)` / `getPageList(html, url)` / `getChapterText(html, url)`,
`getFilterList()`, `getSettingsSchema()`.

Фильтры: `select/sort/text/switch` передаются плагину строкой, остальные
(checkbox/tristate/tag_input) — массивом; пустая выборка опускается, чтобы в
плагине работал идиоматичный `filters["sort"] or "latest"` (паритет с NoveLA
`LuaFilterSupport`).

Глобалы скрипта, читаемые движком при загрузке (после выполнения чанка,
регистрация по хосту baseUrl, снимается при `dispose()`): `referer` (дефолтный
`Referer`, когда вызывающий его не прислал — хотлинк-каверы),
`getUserAgentPreset()` (имя пресета → per-source + per-host UA),
`cf_options` (`whitelist`/`ignore_markers`/`trigger_markers` → `CfOptions`),
`charset` (декодирование ответов по умолчанию, например `GBK`).

### 2.3 Каталог Lua-биндингов

Устанавливаются **до** выполнения чанка плагина (можно звать `require_lib` и
`html_parse` на верхнем уровне).

| Группа | Глобалы |
|---|---|
| Сеть (`NetworkBindings`) | `http_get`, `http_post`, `http_get_batch`, `sleep` |
| HTML (`HtmlBindings`, Ksoup) | `html_parse`, `html_select`, `html_select_first`, `html_text`, `html_attr`, `html_html`, `html_outerHtml`, `html_absUrl`, `html_remove` + методы узла `get_text/get_html/attr/select/remove` |
| Regex/строки (`StringBindings`) | `regex_match`, `regex_replace`, `string_normalize`, `string_split`, `string_trim`, `string_starts_with`, `string_ends_with`, `string_clean`, `unescape_unicode` |
| JSON (`JsonBindings`) | `json_parse`, `json_stringify` |
| Крипто/кодеки (`CryptoBindings`) | `sha256`, `md5`, `hmac_sha256`, `aes_decrypt`, `aes_gcm_decrypt`, `rc4`, `pbkdf2`, `base64_encode`, `base64_decode_bytes`, `inflate`, `unpack_packed`, `json_encode` |
| Хранилище/хост (`StorageBindings`) | `get_cookies`, `set_cookies`, `get_localStorage`, `set_localStorage`, `get_preference`, `set_preference`, `show_error` |
| Служебные | `log_info`, `log_error` (`LogBindings`), `os_time` (`OsBindings`), `detect_pagination`, `get_user_agent`, `base64_decode` (`MiscBindings`), `pow_solve` (`PowSolver`), `url_encode`, `url_encode_charset`, `url_resolve` (`UrlBindings`), `require_lib` (`LibraryLoader`) |

Формат ответа HTTP в Lua (паритет NoveLA): `{ success, code, status, charset,
headers, body }`; ошибка моста — `{ success=false, code=-1, body, error,
errorType }`, где `errorType ∈ SsrfBlocked | DnsFailure | CfChallengeRequired |
ResponseTooLarge | Timeout | Network`. `http_get_batch` — `maxConcurrent`
1..32 (дефолт 4), `binary=true` отдаёт тело массивом байт.

**Песочница** (`Sandbox.kt`): вырезаны `luajava`, `io`, `load`, `loadfile`,
`loadstring`, `dofile`, `require`, `package`, `debug`, `coroutine`; `os`
обрезан до `time`/`date`. `LuaValue`/`LuaTable` наружу не выходят — на границе
`LuaArg`/`LuaData`. Сессия однопоточная: адаптер сериализует вызовы мьютексом и
прыгает в `Dispatchers.IO` (сетевой мост внутри LuaJ использует `runBlocking`).

`require_lib(id [, sha])`: инстанс таблицы на сессию, `__index` метатаблицы
смотрит в globals текущей сессии, кеш скомпилированного байткода ключуется по
sha256 файла (смена либы инвалидирует кеш), переданный sha проверяется.

**Мультиязычность плагинов**: `metadata.language` (дефолт `en`, читается из
глобала скрипта), `Accept-Language` подставляется из `SystemInfo.acceptLanguage`
в каждый запрос, индекс `index.yaml` бывает языковым (`sources:` — в языковом
файле, `libraries:` — в корневом), UA-пресеты (`UserAgentPresets` + алиасы).
Локализация самого UI — на стороне хоста.

### 2.4 Сеть

`PluginHttpClient` (общий для всех плагинов код, клиент — инстанс на плагин):

- дефолтные заголовки: полный Chrome-набор (`sec-ch-ua` с GREASE, `sec-fetch-*`,
  `upgrade-insecure-requests`, `priority`) — только при Chromium-UA, `Accept` по
  типу запроса, `Referer = scheme://host/`, `Accept-Encoding` из того же списка,
  что и декодирование; заголовки вызывающего всегда выигрывают
  (`PluginRequestBuilder`);
- ручной мост cookie: `CookieStore` хоста → `Cookie` на запрос, `Set-Cookie` обратно
  (плагин `ktor-client-cookies` не используется);
- **декомпрессия** gzip/deflate/`br` (`ktor-client-encoding`, чистый Java
  `org.brotli:dec` без JNI);
- **редиректы вручную** (`RedirectFollower`), каждый хоп заново прогоняется через
  SSRF-guard; плюс JS/meta-навигации (`JsRedirectResolver`: `window.location*`,
  `location.replace/assign`, meta-refresh) на HTML-GET — с `Referer` страницы,
  SSRF-проверкой и общим лимитом хопов; `followRedirects=false` отключает и их;
- **retry**: `429/502/503/504`, только GET, до 4 повторов, backoff от 500 мс
  (экспоненциальный), бюджет-вызов (`timeoutMs`) — одна попытка без sleep;
  CF-челлендж ретраем не прожигается;
- **транспортная лесенка** (паритет NoveLA `NetworkClient`): до 5 попыток GET с
  таймаутами фаз `3/3/3/3/15 с` и джиттером 0–400 мс, сон обрезан остатком
  deadline; бюджет-вызов — одна попытка; потолок: `evictAll` пула перед
  ретраем (NoveLA) не портирован — у Ktor engine abstraction нет хэндла пула,
  дохлый коннект и так не переиспользуется;
- **каталог — всегда свежая сеть**: `catalog()`/`catalogFiltered()` идут мимо
  кеша (но освежают его), `search()` — нет (паритет NoveLA `withForceNetwork`
  на list/filtered);
- **deadline**: 90 с на весь вызов (монотонные часы, проверки на границах, а не
  coroutine-таймер), connect/socket timeout 10 с;
- **лимит тела**: 25 МБ (`BodySizeLimiter`) → `PluginError.ResponseTooLarge`;
- **кеш ответов** `ResponseCache` (опционально): LRU 200 записей / 50 МБ /
  TTL 15 мин, ключ SHA-256(method|url|charset|sourceId|заголовки); владение
  кешем у хоста, `null` = выключен (дефолт);
- charset: опция плагина → `Content-Type` → UTF-8 (`CharsetResolver`).
  Опция плагина — это глобал `charset` скрипта (`PluginMetadata.charset`).

### 2.5 Безопасность

- `SsrfGuard`: allow-list схем только `http/https`, блок `localhost`/`*.localhost`,
  литерал IPv4 и **проверка разрешённых адресов** через инжектируемый
  `HostResolver` (на Android/JVM — `InetAddressHostResolver`), fail-closed для
  неоднозначных литералов; вердикт `Ok/Blocked/DnsFailure`. Потолок (записан в
  коде): DNS-rebinding/TOCTOU между проверкой и коннектом не покрыто —
  IP-пиннинга нет.
- Guard включён по умолчанию в `LuaPluginSourceFactory`
  (`ssrfGuard = SsrfGuard(InetAddressHostResolver())`), выключается явно
  передачей `null` или своего guard'а.
- Песочница Lua (см. §2.3), path-safe проверка `id` в инсталлере
  (`[A-Za-z0-9_.-]+`, `..`/`.` запрещены), `require_lib` — `[a-z0-9_]+`.
  Сознательно строже NoveLA (`isValidExtensionId` там режет только `/`, `\`,
  `..`): весь корпус (93 id индекса + все `id` скриптов, включая
  `mvlempyr.com`, `bad-fixture`, `NovelArrow`) проходит наш паттерн, а более
  широкий ничего не даёт — только поверхность для path-traversal.
- sha256-верификация при установке и загрузке скриптов, атомарный коммит
  (tmp + rename) — битая установка не трогает уже установленный файл.

### 2.6 Cloudflare

- `CloudflareDetector`: статусы `403/429/503` + маркеры тела (`Just a moment`,
  `__cf_chl_`, turnstile, ddos-guard и др.), свои `whitelist`/`ignore_markers`/
  `trigger_markers` из глобала `cf_options` скрипта; статические ассеты по
  расширению (`STATIC_EXTENSIONS` NoveLA) — не челлендж; встроенный
  host-whitelist (`github.com`, `raw.githubusercontent.com`, `images.weserv.nl`,
  `wsrv.nl`); URL-маркеры жёсткого IP-бана (`/cdn-cgi/error/`, `error=1020/1015`)
  — челлендж без corroboration;
- `CfGate`: per-host lock (30 с на захват) + cooldown — 120 с после успеха,
  60 с терминальный после провала (значения NoveLA: `COOLDOWN_MS`,
  `TERMINAL_COOLDOWN_MS`); повторный вызов во время cooldown быстро падает
  `CfChallengeRequired`, а не крутит solver заново. Повторный solve чистит
  ТОЛЬКО `cf_clearance` (`CookieStore.clearCookies(host, names)`, NoveLA
  `clearCookiesForDomain`), остальные куки выживают;
- `CfChallengeSolver` — интерфейс хоста (`solve(CfChallenge): CfSolution?`):
  в проде это WebView/handler на стороне приложения, в дев-тулсе — внешний
  процесс (см. §6). Хост обязан валидировать clearance живым запросом
  (паттерн NoveLA `probeIsClear`: кука сама по себе ничего не доказывает) —
  WebView в core нет, проба — код хоста. После решения — один повтор запроса, полученные cookies
  пишутся в `CookieStore`, эффективный UA браузера — в `HostOptionsRegistry`.
- `PluginMetadata.cfOptions`/`userAgentPreset` регистрируются в
  `HostOptionsRegistry` при `install` и снимаются при `dispose()`.

### 2.7 Cookie / UA persistence

- `CookieStore` (`get/setFromResponse/clear/clearCookies`) и `KeyValueStore`
  (`get/set/remove` по `namespace=key`, namespace = `sourceId`) реализует хост —
  persistence целиком на его стороне (файл/БД/SharedPreferences), включая
  выживание `cf_clearance` между перезапусками (иначе — свежий bypass при
  каждом запуске; core сам ничего не персистит — KMP-ядро не владеет Android
  CookieManager).
- UA-резолюция: `PluginMetadata.userAgentPreset` → `PluginUserAgentRegistry`
  (по sourceId, затем по host с обходом родительских доменов) → `UserAgentPresets.DEFAULT`
  (полный Chromium-UA). Пресеты: Chrome/Safari/Firefox/Edge/Samsung, desktop и
  mobile, + алиасы (`"Chrome Mobile"` и т.п.). Запрос никогда не уходит с
  пустым или «небраузерным» UA.

### 2.8 Установка и обновление плагинов

- `parsePluginIndex(yaml)` (snakeyaml, safe-constructor): `sources:` + `libraries:`,
  поля `id/name/version/url/sha256/api`.
- `PluginInstaller(fileSystem, fetcher, installDir="lua_plugins", libDir="lua_libs")`:
  валидация `id` → api-gate → скачивание → sha256 → проверка синтаксиса в чистой
  песочнице (без выполнения) → парсинг метаданных шапки → атомарный коммит;
  `installLibrary/installLibraries` кладёт либы в `libDir` под `require_lib`.
- `isUpdateAvailable(entry)` — сравнение только по sha256 (версии не участвуют).
- `ScriptLoader`: sha-проверка, атомарная запись, кеш байткода `.lbc`
  (тег формата `LUAC_TAG = "3.0.1"` — смена версии LuaJ инвалидирует кеш).
- `ScriptMetadataParser`: читает шапку скрипта (`id/name/version/baseUrl/language/
  content_type/icon/description/charset/referer/cf_options`), `content_type`
  валидируется (`novel|manga|video`), `language` нормализуется (`mtl/multi` →
  `mtl`, иначе lowercase/trim; паритет NoveLA без `LanguageCode`), дефолт `en`.
  `toPluginMetadata` пробрасывает `icon/description/charset/cf_options` в
  `PluginMetadata` без потерь.

### 2.9 Локальные источники и видео

- `LocalSource(fileSystem, libraryDir, contentType)` — `PluginSource` поверх
  каталога файлов; парсеры `EpubParser`, `Fb2Parser`, `CbzParser` (+ `ZipArchive`,
  `LocalXml`) в `jvmSharedMain`. Потолок: книга парсится заново на каждый вызов.
- `video/StreamResolver`: `pickStream(streams, preferQuality)` (точное совпадение
  качества, иначе первый элемент — семантика эталона) и `hasMediaExt(url)`
  (`.m3u8/.mp4/.mkv`).

### 2.10 Трассировка запросов

`NetworkTraceEvent(method, url, status, requestHeaders, responseHeaders,
bodyBytes, durationMs, cfBlocked, redirectHop)` + callback `trace` в
`LuaPluginSourceFactory`/`PluginHttpClient`. Один эмит на попытку **после** чтения
тела (bodyBytes — реальный размер). Дефолт `null` — путь приложения: ни одно
поле не считается, детект CF для трассы не выполняется. Потребитель (plugin-tester)
рисует из этого секцию `NETWORK TRACE`.

### 2.11 Хост-контракты (реализует потребитель)

| Интерфейс | Назначение |
|---|---|
| `FileSystem` | `read/write/exists/delete/rename/list` для скриптов, либ, плагинов |
| `KeyValueStore` | `get/set/remove(namespace, key)` — настройки и localStorage плагинов |
| `CookieStore` | `get(url)/setFromResponse(url, setCookieHeaders)/clear(host)/clearCookies(host, names)` |
| `Logger` | `d/w/e(tag, msg, t?)` |
| `SystemInfo` | `defaultUserAgent`, `acceptLanguage` |
| `CfChallengeSolver` | опциональный CF-bypass (`solve(CfChallenge): CfSolution?`) |

---

## 3. Source set'ы: что где лежит

```
mirutime-core/src
├── commonMain      # api/ (PluginSource, PluginRegistry, Types, Outcome, PluginError),
│                   # network/ (PluginHttpClient, RequestBuilder, RedirectFollower,
│                   #   retry, deadline, cache, cf/, security/, trace), host/,
│                   # video/, engine/LuaEngineFacade (expect, без LuaJ-типов)
├── jvmSharedMain   # engine/ (LuaJ-движок, Sandbox, все binding/, adapter/LuaPluginSource,
│                   #   loader/), installer/ (Index/Plugin/ScriptMetadata), local/ (epub/fb2/cbz),
│                   #   CharsetResolver, BrotliEncoder, InetAddressHostResolver
├── androidMain     # createEngine() = OkHttp, currentTimeMillis() actual   → в AAR
├── jvmMain         # createEngine() = CIO,    currentTimeMillis() actual   → только JVM
├── commonTest      # тесты (не публикуются)
└── jvmTest         # 430 тестов / 41 suites (не публикуются)

plugin-tester/src   # CLI-тул: Main, TestRunner, Host (десктоп-реализации хост-контрактов),
                    # CfSolver (python3) — ВЫНЕСЕН в ../mirutime-plugin-tester (см. §6)
```

---

## 4. Интеграция: финальная схема (AAR-артефакт)

**Решено:** и ридер-приложение, и тул `mirutime-plugin-tester` (отдельное репо)
подключают либу одинаково —
**AAR-артефактом**: в деве из `mavenLocal()`, в релизах из JitPack с пином
версии (альтернатива — артефакты GitHub Release). Композитная сборка (вариант
(a)) остаётся вспомогательным инструментом для правок исходников либы на лету,
копия модуля (вариант (c)) — крайний случай, не рекомендуется.

| Сценарий | Репозиторий | Координаты |
|---|---|---|
| дев | `mavenLocal()` (`publishToMavenLocal` перед сборкой ридера/тула) | `dev.mirutime:mirutime-core:0.1.0` |
| релиз | JitPack (или артефакты GitHub Release), версия пиннится явно | `com.github.<owner>:mirutime-core:<tag>` |

### (a) Composite build: `includeBuild("../MiruTime-Core")` — только для правок исходников

```kotlin
// settings.gradle.kts потребителя
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
    // + mavenLocal()/jitpack — если используете вариант (b)
}
includeBuild("../MiruTime-Core")

// build.gradle.kts потребителя
dependencies {
    implementation("dev.mirutime:mirutime-core:0.1.0") // версия — любая, её заменяет substitution
}
```

Градл подставит проект `:mirutime-core` вместо модуля `dev.mirutime:mirutime-core`
(совпадают `group` + имя модуля `mirutime-core`). Сборка потребителя при этом
компилирует либу из исходников — правки в либе видны сразу.

> `implementation(project(":mirutime-core"))` напрямую между разными git-репо
> **не работает**: `project(...)` видит только модули своего build'а. Либо
> `includeBuild` + координаты (см. выше), либо копия модуля внутри репо
> (вариант (c)), где `include(":mirutime-core")` делает `project()` доступным.

**Что тянется за собой** (runtime classpath, android-вариант):
Ktor 3.6.0 (`ktor-client-core` + engine `ktor-client-okhttp` + `ktor-http/io/utils/
events/websockets/http-cio/network/sse/serialization/encoding`), OkHttp 5.5.0,
`kotlinx-coroutines-core`, `kotlinx-serialization-json`, Ksoup 0.2.6 (+ `fleeksoft.io`,
`fleeksoft.charset`, `stately`), okio 3.18.2, LuaJ 3.0.1, snakeyaml 2.7,
`org.brotli:dec` 0.1.2. На **compile** classpath потребителя дополнительно
появляются три `api`-зависимости либы: `kotlinx-coroutines-core`,
`ktor-client-core`, `kotlinx-serialization-json` — их версии могут перебиться
версиями самого приложения (конфликт-резолюция Gradle).

### (b) AAR-артефакт — основная схема

**Дев** (нужен Android SDK):

```bash
# из этого репо (для android-публикации нужен Android SDK)
ANDROID_HOME=~/Library/Android/sdk ./gradlew :mirutime-core:publishToMavenLocal
```

```kotlin
// settings.gradle.kts потребителя — одинаково для ридера и для mirutime-plugin-tester
dependencyResolutionManagement { repositories { mavenLocal(); google(); mavenCentral() } }
// build.gradle.kts
dependencies { implementation("dev.mirutime:mirutime-core:0.1.0") }
```

Фактически публикуются три артефакта (проверено `publishToMavenLocal`):

| Координаты | Файл | Назначение |
|---|---|---|
| `dev.mirutime:mirutime-core:0.1.0` | jar 826 Б | корневая KMP-публикация (метаданные, tooling-metadata) |
| `dev.mirutime:mirutime-core-android:0.1.0` | `.aar` 487 162 Б | Android-вариант, выбирается автоматически |
| `dev.mirutime:mirutime-core-jvm:0.1.0` | jar 523 412 Б | JVM-вариант, выбирается автоматически |

Задачи публикации: `publishToMavenLocal` (все), либо поимённо
`publishAndroidPublicationToMavenLocal`, `publishJvmPublicationToMavenLocal`,
`publishKotlinMultiplatformPublicationToMavenLocal`. Выбор артефакта
потребителем происходит по variant matching — вручную `-android`/`-jvm`
указывать не нужно.

**Релиз.** Версия пиннится явно (`…:mirutime-core:<tag>`) — JitPack или
артефакты GitHub Release, никакого `+`/`latest`:

JitPack: `jitpack.yml` запускает
`./gradlew :mirutime-core:publishToMavenLocal -Pgroup=$GROUP -Pversion=$VERSION`
(JDK 17), то есть артефакты отдаются под `com.github.<owner>:mirutime-core:<tag>`
(имя модуля `mirutime-core`, не имя репо). Потребитель подключает их добавлением
`maven("https://jitpack.io")` в `dependencyResolutionManagement` и пином на тег.
Живой прогон через jitpack.io в этой сессии не выполнялся.

**GitHub Action на теги** (рекомендуемый workflow — сниппет для документации,
файла `.github/workflows/` в репо пока нет):

```yaml
name: publish-aar
on:
  push:
    tags: ['v*']
jobs:
  release:
    runs-on: ubuntu-latest          # в образе runner'а уже есть Android SDK
    permissions: { contents: write } # нужен, чтобы положить файл в Release
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - run: ./gradlew :mirutime-core:bundleAndroidMainAar
      - uses: softprops/action-gh-release@v2
        with: { files: mirutime-core/build/outputs/aar/mirutime-core.aar }
```

Ридер и тул `mirutime-plugin-tester` подключают единообразно: для JitPack —
`implementation("com.github.<owner>:mirutime-core:<tag>")` (+ репозиторий
`maven("https://jitpack.io")`); для артефактов GitHub Release — скачанный AAR
кладётся в свой maven-репозиторий (или в GitHub Packages) и указывается его
URL. Меняется только репозиторий и версия, сама зависимость та же; версия в
обоих случаях пиннится на тег.

**Что тянется** — тот же список, что в (a); разница только в том, откуда берётся
код либы.

**Что НЕ нужно объявлять в приложении:**

- **OkHttp напрямую не объявлять** — он приходит транзитивно через
  `ktor-client-okhttp` (движок клиента либы). Прямая запись
  `implementation("com.squareup.okhttp3:okhttp:…")` нужна только UI-слою,
  который сам создаёт OkHttp-клиенты — `coil-network-okhttp`,
  `media3-datasource-okhttp`; Gradle всё равно сведёт всё к одной копии
  (конфликт-резолюция), вторая зависимость ничего не добавляет.
- **Ktor/OkHttp/Ksoup/okio/coroutines/kotlinx-serialization** — уже в списке
  «что тянется» выше, отдельно их в `dependencies` приложения класть не надо.

**R8 / consumer-правила:** AAR отдаёт свои R8-правила (`proguard.txt` внутри
AAR, см. `mirutime-core/consumer-rules.pro`) — keep для LuaJ (`org.luaj.**`) и
snakeyaml (`org.yaml.snakeyaml.**`), больше ничего. Правила подтягиваются
автоматически, в `proguard-rules.pro` приложения их дублировать не нужно.
Для приложения рекомендуется **R8 full mode** (дефолт AGP 8+) — так проверяется
именно та конфигурация, под которую правила писались.

### (c) Копия модуля как source-set — крайний случай, не рекомендуется

По решению пользователя основная схема — AAR (§4 (b)); копирование модуля
допустимо, только если либу нельзя ни опубликовать, ни подключить через
`includeBuild`. Что копировать в этом случае:

1. папку `mirutime-core/` целиком (`build.gradle.kts` + `src/` +
   `consumer-rules.pro`);
2. записи из `gradle/libs.versions.toml` (или жёстко прописать coordinates в
   `build.gradle.kts` модуля): плагины `org.jetbrains.kotlin.multiplatform`,
   `com.android.kotlin.multiplatform.library`, и библиотеки
   `kotlinx-coroutines-core`, `ktor-client-core`, `ktor-client-encoding`,
   `ktor-client-okhttp`, `ktor-client-cio`, `kotlinx-serialization-json`,
   `ksoup`, `okio`, `luaj-jse`, `snakeyaml`, `brotli-dec`;
3. в `settings.gradle.kts` потребителя: `include(":mirutime-core")` и репозитории
   `google()` + `mavenCentral()`;
4. в корневой `build.gradle.kts` потребителя: те же два плагина с `apply false`
   (как в этом репо).

`plugin-tester` копировать **не нужно** (dev-only), `maven-publish` — тоже
(публикация не требуется). Минус варианта: обновления либы приходится тащить
вручную. Плюс: `project(":mirutime-core")` работает как обычный модуль.
`Сборка и тесты` — см. §7; для android-таргета нужен Android SDK
(compileSdk 37, minSdk 26), `local.properties` в `.gitignore`.

---

## 5. Размер в APK

### 5.1 Таблица «что куда включается»

| Кусок | Android AAR | JVM jar | `mirutime-plugin-tester` | APK потребителя |
|---|---|---|---|---|
| `mirutime-core/commonMain` | ✅ | ✅ | ✅ (через артефакт) | ✅ |
| `mirutime-core/jvmSharedMain` (LuaJ-движок, биндинги, инсталлер, парсеры книг) | ✅ | ✅ | ✅ | ✅ |
| `mirutime-core/androidMain` (OkHttp engine) | ✅ | ❌ | ❌ | ✅ |
| `mirutime-core/jvmMain` (CIO engine) | ❌ | ✅ | ❌ | ❌ |
| `mirutime-core/consumer-rules.pro` (R8 keep для LuaJ/snakeyaml) | ✅ (в AAR как `proguard.txt`) | ❌ | ❌ | ✅ (правила применяются R8'ом приложения) |
| `commonTest` / `jvmTest` (430 тестов) | ❌ | ❌ | ❌ | ❌ |
| `mirutime-plugin-tester` (классы, jar) | ❌ | ❌ | ✅ (своя сборка) | **❌ 0 байт** |
| `mirutime-plugin-tester/cf_solve.py`, `.cf_cookies.json` | ❌ | ❌ | файлы рядом с рабочим каталогом | **❌ 0 байт** |
| python3 / DrissionPage / Brave | ❌ | ❌ | внешний процесс дев-машины | **❌ 0 байт** |
| `gradle/libs.versions.toml`, `jitpack.yml`, `docs/` | ❌ | ❌ | ❌ | ❌ (build-time only) |

### 5.2 Цифры (измерено в этой сессии, до R8)

Собственный код библиотеки:

| Артефакт | Размер |
|---|---|
| `mirutime-core-android-0.1.0.aar` | 487 162 Б (476 КБ), внутри `classes.jar` 523 237 Б / 274 класса + `proguard.txt` 3 908 Б |
| `mirutime-core-jvm-0.1.0.jar` | 523 412 Б (511 КБ) |
| корневой KMP-jar | 826 Б |

Runtime classpath (сумма всех разрешённых артефактов, **до** shrink'а —
команда: `./gradlew :mirutime-core:dependencies --configuration androidRuntimeClasspath`):

| Группа | Android | Комментарий |
|---|---|---|
| Ktor + engine OkHttp | **3.56 МБ** | `ktor-client-core` 940 КБ, `okhttp.aar` 924 КБ, `ktor-http` 448 КБ, `ktor-utils` 388 КБ, `ktor-io` 296 КБ, `ktor-network` 197 КБ, `ktor-websockets` 188 КБ, `ktor-http-cio` 131 КБ, остальное < 100 КБ |
| `kotlin-stdlib` 2.4.20 | 1.77 МБ | |
| `kotlinx-coroutines-core` | 1.51 МБ | в `libs.versions.toml` пин 1.9.0, но Ktor 3.6.0 поднимает до **1.11.0** |
| Ksoup + `fleeksoft.io` + `charset` + `stately` | 0.68 МБ | |
| `kotlinx-serialization` core+json | 0.67 МБ | |
| okio | 0.37 МБ | |
| LuaJ 3.0.1 | 0.34 МБ | |
| snakeyaml 2.7 | 0.32 МБ | нужен только `IndexParser` |
| kotlinx-io (транзит Ktor) | 0.17 МБ | |
| slf4j/annotations/androidx.startup+tracing | 0.18 МБ | транзит OkHttp/Ktor |
| `org.brotli:dec` | 0.09 МБ | |
| **Итого Android** | **≈ 9.65 МБ** | JVM-вариант: **≈ 8.99 МБ** (вместо OkHttp — CIO 135 КБ + `ktor-network-tls` 194 КБ) |

Это размеры нераспакованных jar/aar до R8: в финальный APK попадают классы после
tree-shaking и сжатия, то есть существенно меньше. Конкретную цифру даёт только
minify-сборка самого приложения — в этом репо APK не собирается.

### 5.3 Что реально уменьшает APK

1. **R8/minify у потребителя — главный рычаг.** AAR отдаёт свои consumer-правила
   (`proguard.txt` внутри артефакта, 3 908 Б, исходник —
   `mirutime-core/consumer-rules.pro`): два keep, `org.luaj.**` и
   `org.yaml.snakeyaml.**` — обе либы резолвят классы/методы по имени через
   рефлексию и своих правил не имеют. Правила подтягиваются автоматически,
   дублировать их в `proguard-rules.pro` приложения не нужно. Обратная сторона:
   snakeyaml (332 КБ) теперь **не** режется R8'ом — он зафиксирован целиком
   (потолок записан в комментариях `consumer-rules.pro`). Всё остальное —
   Ksoup, okio, brotli, неиспользуемые движки/кодеки Ktor — режется свободно.
   Своя строка в `proguard-rules.pro` приложения нужна только под рефлексию
   самого приложения. Факт «работает/не работает с R8 full mode» здесь не
   проверялся — проверять на реальной минифицированной сборке ридера.
2. **Движок уже разведён по таргетам.** Android-вариант тянет только OkHttp,
   JVM — только CIO; `ktor-client-cio` в Android-класспайте нет, ничего выкидывать
   вручную не надо. Отдельный `ktor-client-*` подключать не нужно.
3. **`implementation`, не `api`, у «тяжёлых» внутренностей.** Ksoup, okio, LuaJ,
   snakeyaml, brotli-dec, `ktor-client-encoding` — `implementation`: на
   compile classpath потребителя не всплывают, доступны только через Gradle
   module metadata в runtime. `api` — только coroutines, `ktor-client-core`,
   serialization-json (без них типы API не скомпилировать).
4. **Тесты и тул не въезжают.** `commonTest`/`jvmTest` не публикуются,
   тул `plugin-tester` вынесен в отдельный репозиторий `../mirutime-plugin-tester`
   (0 байт, проверено по составу AAR).
5. **Не отключаемые куски — называю честно:**
   - *video-биндинги вырезать нельзя*: диспетчер `getVideoList/getPageList/
     getChapterText` и `StreamResolver` лежат в `jvmSharedMain` и всегда в
     артефакте. Их цена — единицы КБ, экономить там нечего.
   - *CF, SSRF, trace, кеш* — тоже не опциональные флаги сборки: это код в том же
     source set. Trace при `trace = null` просто ничего не считает (ноль
     накладных в рантайме), остальное — минимальный вклад в общий размер.
   - *snakeyaml/luaj отдельными «фича-флагами»* не отключаются — зависимость
     объявлена в `build.gradle.kts` модуля, и теперь их не вырезает и R8:
     consumer-правила AAR держат оба пакета целиком (~680 КБ на двоих,
     потолок и путь к сужению — в комментариях `consumer-rules.pro`).
6. **Не тащить лишнего в API-поверхность**: новые зависимости в либу не
   добавляются без необходимости (правило проекта), так что список §4 —
   закрытый набор.

---

## 6. Как гонять плагины (`mirutime-plugin-tester`)

Dev-тул **вынесен из этого репо** в отдельный проект
`/Users/mac/Documents/GitHub/mirutime-plugin-tester` — обычная JVM-консоль
(`application`, `mainClass = dev.mirutime.tester.MainKt`), использует **ту же**
либу и тот же HTTP-стек, что и приложение (второго движка и второго клиента в
нем нет), подключая её опубликованным артефактом
`dev.mirutime:mirutime-core-jvm:0.1.0` из `mavenLocal()` (§4b). Формат вывода,
секции и exit codes описаны ниже — тул не менялся при выносе, только способ
подключения к либе.

```bash
# 1) один раз (и после каждой правки либы) — опубликовать либу локально:
#    из ЭТОГО репо, нужен Android SDK
ANDROID_HOME=~/Library/Android/sdk ./gradlew :mirutime-core:publishToMavenLocal

# 2) минимально — из соседнего репо
cd /Users/mac/Documents/GitHub/mirutime-plugin-tester
./gradlew --console=plain -q run --args="path/to/plugin.lua"

# с URL-ами (иначе возьмёт первый из каталога/списка глав)
./gradlew --console=plain -q run --args="plugin.lua --url https://site.test/book/123 --chapter-url https://site.test/book/123/4"
```

Аргументы:

| Аргумент | Смысл |
|---|---|
| `<plugin.lua>` | обязательный, путь к скрипту |
| `--url <url>` | URL книги для detail-тестов (иначе автоподбор из `catalog[1].url`) |
| `--chapter-url <url>` | URL главы (иначе автоподбор из `chapter_list[1].url`) |
| `--auth` | authenticated-режим (CF bypass + cookies) |
| `--cf` | no-op: CF-bypass всегда автоматический (cookies + solver по требованию) |

Переменные окружения: `LUA_LIBS_DIR` — каталог для `require_lib` (иначе тул сам
копирует `*.lua` из `../external-sources/libs` или `../../external-sources/libs`
в `build/lua_libs`), `CF_SOLVE_SCRIPT` — путь к CF-скрипту.

**CF solver (только дев-машина, в прод не входит):** `mirutime-plugin-tester/cf_solve.py`
запускается как `python3 <script> <url> <timeout>`, требует `DrissionPage` и
браузер Brave (путь захардкожен: `/Applications/Brave Browser.app/Contents/MacOS/Brave`,
то есть сейчас это macOS-скрипт). Куки сохраняются в
`mirutime-plugin-tester/.cf_cookies.json` вместе с UA, при следующем запуске UA
воспроизводится (cf_clearance валиден только с тем же UA). Solver вызывается
не более **один раз на хост за прогон**; при отсутствии скрипта/питона тул
деградирует в `CfChallengeRequired` и продолжает работать. Ни один из этих
файлов не попадает в библиотеку и в APK.

**Формат вывода:**

1. баннер + `Plugin Info` (name/version/baseUrl/language/content type/load time);
2. `Functions defined:` / `Functions missing:` (12 канонических точек входа +
   расширенный набор: `getBookGenres`, `getBookLastUpdate`, `getBookChapterCount`,
   `getBookExtras`, `parsePage`, `getVideoList`);
3. блоки на каждый тест: `── <name> [PASS|FAIL] (<ms>) ──` + `✗ ошибки` +
   `· ключ: значение`;
4. сводка: `N/M passed · ALL PASSED | K FAILED · <ms> total`;
5. `NETWORK TRACE — <n> requests`: по каждой попытке метод, URL, статус
   (`✓/↗/✗`, `⚠ CF-BLOCKED`), размер тела, длительность, `hop`, отправленные
   заголовки (Cookie обрезается до 80 символов) и часть ответных
   (`set-cookie`, `location`, `content-type`).

Секции тестов: `metadata`, `catalog`, `auto_book_url`, `search`, `pagination`,
`book_title`, `book_cover`, `book_description`, `book_status`, `book_rating`,
`book_extras`, `chapter_list`, `auto_chapter_url`, затем по contentType —
`chapter_text` (novel) / `page_list` (manga) / `video_streams` (video), плюс
`filters` (если объявлен `getFilterList`), `plugin_events`, `site_health`.
Наличие `video_streams`/`page_list` определяется `content_type` скрипта.

**Exit codes:**

| Код | Когда |
|---|---|
| `0` | все тесты прошли |
| `1` | нет аргументов (печатает usage), файл не найден, `LOAD FAILED` (ошибка при загрузке чанка), либо любой тест `FAIL` |

Через Gradle ненулевой код валит задачу: `./gradlew --console=plain -q run`
(в `../mirutime-plugin-tester`) завершается `BUILD FAILED` с
`finished with non-zero exit value 1` (проверено на
фиктивном скрипте: `2/4 passed · 2 FAILED` → код 1).

---

## 7. Сборка и проверка

```bash
./gradlew :mirutime-core:jvmTest                                        # 430 тестов, 41 suites
ANDROID_HOME=~/Library/Android/sdk ./gradlew :mirutime-core:compileAndroidMain
ANDROID_HOME=~/Library/Android/sdk ./gradlew :mirutime-core:publishToMavenLocal
# тул (отдельное репо ../mirutime-plugin-tester): сначала publishToMavenLocal выше
cd ../mirutime-plugin-tester && ./gradlew --console=plain -q run --args="plugin.lua"
```

Android-задачи падают с `SDK location not found`, если не задан `ANDROID_HOME`
или `sdk.dir` в `local.properties` (файл в `.gitignore`).

---

## 8. Статус и открытые решения

- **Extras + GET-memo доставлены.** `BookMeta.extras` (произвольные метаданные:
  неизвестные ключи legacy `getBookMeta` + таблица опционального расширения
  MiruTime `getBookExtras(url)`, в NoveLA отсутствует; известные ключи
  отфильтровываются — extras никогда не переопределяют канонические поля) и
  URL-мемо `http_get`, активный только на время агрегации геттеров внутри
  `book()` (некэширующие плагины били в сайт ~7 раз на одну книгу — теперь
  один раз; `http_post`/`http_get_batch` и вызовы вне `book()` не мемоизируются).
- **`regex_match`: массив (решено).** Возвращает 1-based массив полных
  совпадений (`m[1]`, `m[2]`, …), как NoveLA; нет совпадений или ошибка
  регулярки → пустая таблица. Бывший boolean-«controller ruling»
  (`containsMatchIn`) отменён по решению пользователя: в корпусе плагинов
  **11 из 96** индексируют результат как массив (`m[1]` и т.п.), **0**
  используют его как boolean.
- **`plugin-tester` вынесен в `../mirutime-plugin-tester`** — отдельный
  standalone-проект (свой `settings.gradle.kts`/`build.gradle.kts`, свой gradle
  wrapper), подключает либу по §4b: координаты
  `dev.mirutime:mirutime-core-jvm:0.1.0` из `mavenLocal()` (в деве) или
  release-артефакта JitPack. `project(":mirutime-core")` между разными репо не
  работает, поэтому обязательный шаг перед прогоном — `publishToMavenLocal` (§6).
  Ядро при этом остаётся чистым: в `classes.jar` AAR нет ни одного класса
  `dev.mirutime.tester`.
- **Коммитов нет**: изменения в рабочем дереве не закоммичены
  (`git status` — изменены `settings.gradle.kts`, `build.gradle.kts`,
  `libs.versions.toml`, `mirutime-core/build.gradle.kts`, `PluginHttpClient`,
  `LuaPluginSource`, `HtmlBindings`, `ScriptMetadataParser`, `README.md`, тесты;
  добавлены `NetworkTraceEvent.kt`, `PluginHttpClientTraceTest.kt`,
  `mirutime-core/consumer-rules.pro`; каталог `plugin-tester/` удалён из репо —
  теперь это соседний репозиторий `../mirutime-plugin-tester`).
- **Не проверено/не сделано:** живой прогон JitPack; работа с R8 full mode
  (consumer-правила в AAR есть — §4/§5.3, но минифицированной сборкой ридера
  не проверялись); iOS-таргет; распределение `includeBuild` между разными
  ветками; `.github/workflows/` — сниппет workflow в §4 (b) только
  документация, файла в репо нет.

---

## 9. Лицензия

GPL-3.0-or-later © 2026 HnDK0, см. LICENSE.
