package dev.mirutime.core.host

import dev.mirutime.core.network.security.SsrfGuard
import dev.mirutime.core.network.security.SsrfVerdict

data class CfChallenge(
    val host: String,
    val url: String,
    val status: Int,
    val markers: List<String>,
    val referer: String? = null,
) {
    // NoveLA parity (CloudfareVerificationInterceptor.kt:302-306): a challenge
    // on an /api/ path has no browser JS context — the solver opens the
    // REFERER page (a normal site page) instead; the cf_clearance it yields
    // covers the whole host, API endpoints included. No referer -> the
    // request URL stays the solver target.
    val navigateUrl: String
        get() = if ("/api/" in url && !referer.isNullOrBlank()) referer else url

    // The solver target is plugin-derived (caller Referer header or request
    // URL): the host MUST NOT open it in a browser/WebView blindly — an evil
    // plugin could point it at an intranet/metadata address. Returns
    // navigateUrl only when it passes the SSRF guard (http/https + public
    // IP), else null (fail closed, DNS failures included).
    fun validatedNavigateUrl(guard: SsrfGuard): String? =
        if (guard.isSafe(navigateUrl) is SsrfVerdict.Ok) navigateUrl else null
}

data class CfSolution(
    val cookies: Map<String, String>,
    val userAgent: String?,
)

interface CfChallengeSolver {
    // The host MUST open request.validatedNavigateUrl(guard), never the raw
    // navigateUrl — the target is plugin-derived (see above).
    // The host MUST validate the clearance with a live request before
    // reporting success (NoveLA probeIsClear pattern): a cf_clearance cookie
    // alone proves nothing until a request carrying it + the browser UA comes
    // back challenge-free. No WebView lives in core — this probe is host code.
    suspend fun solve(request: CfChallenge): CfSolution?
}
