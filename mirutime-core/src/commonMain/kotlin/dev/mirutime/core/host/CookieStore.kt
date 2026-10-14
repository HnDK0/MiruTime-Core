package dev.mirutime.core.host

data class Cookie(
    val name: String,
    val value: String,
    val domain: String? = null,
    val path: String? = null,
)

interface CookieStore {
    suspend fun get(url: String): List<Cookie>

    suspend fun setFromResponse(url: String, setCookieHeaders: List<String>)

    suspend fun clear(host: String)

    // Removes ONLY the named cookies for a host (NoveLA precedent:
    // clearCookiesForDomain expires just cf_clearance before a fresh bypass —
    // CloudfareVerificationInterceptor.kt:479-494). The client calls this on
    // the re-solve path so a rejected clearance is never re-sent while
    // unrelated cookies (sort order, locale) survive. Hosts SHOULD override
    // for precision; the default falls back to whole-host clear.
    //
    // PERSISTENCE IS A HOST OBLIGATION (README §2.7): the store owns
    // durability — including cf_clearance survival across process restarts.
    // Core never persists cookies itself (KMP core cannot own Android
    // CookieManager); a host that drops the jar on restart forces a fresh
    // bypass on every launch.
    suspend fun clearCookies(host: String, names: List<String>) {
        clear(host)
    }
}
