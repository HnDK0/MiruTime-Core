package dev.mirutime.core.network.cf

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudflareDetectorTest {

    private val detector = CloudflareDetector()

    @Test
    fun markerInBodyIsChallenge() {
        // A real interstitial carries a technical marker (script include), not
        // just the human-readable title.
        assertTrue(
            detector.isChallenge(
                200,
                "<html><title>Just a moment...</title>" +
                    "<form id=\"challenge-form\" action=\"/cdn-cgi/challenge-platform/b/run\"></form></html>",
                null,
            ),
        )
    }

    // The bare phrase is NOT a marker: fictionzone search (search_in_synopsis)
    // returns book synopses containing "Just a moment!" on a server:cloudflare
    // edge — treating it as a challenge made every search fail (false positive).
    @Test
    fun bareJustAMomentPhraseIsNotChallenge() {
        val body = """{"data":{"novels":[{"synopsis":"Domain of Chaos Magic!\n""" +
            """Just a moment!\nLin Han's attributes were increased"}]}}"""
        assertFalse(detector.isChallenge(200, body, null, serverHeader = "cloudflare"))
    }

    @Test
    fun markerMatchIsCaseInsensitive() {
        assertTrue(detector.isChallenge(200, "CF-CHALLENGE in body", null))
    }

    // NoveLA parity (ERROR_CODES branch: hasMarkers && isCfServer): a bare
    // error status with no edge signal and no markers is a transient server
    // error for the status-retry layer, not a challenge.
    @Test
    fun bareErrorStatusWithoutMarkersOrEdgeIsNotChallenge() {
        assertFalse(detector.isChallenge(429, "", null))
        assertFalse(detector.isChallenge(403, "", null))
        assertFalse(detector.isChallenge(503, "", null))
        // The review's case: rate-limit JSON from a non-CF origin.
        assertFalse(
            detector.isChallenge(
                429,
                """{"error":"slow down"}""",
                null,
                serverHeader = "nginx",
            ),
        )
    }

    @Test
    fun errorStatusCorroboratedByMarkersOrEdgeIsChallenge() {
        // Marker corroboration (no Server header at all).
        assertTrue(detector.isChallenge(429, "x __cf_chl_ y", null))
        assertTrue(detector.isChallenge(503, "cf-challenge", null))
        // Edge corroboration (headers alone, empty body).
        assertTrue(detector.isChallenge(403, "", null, serverHeader = "cloudflare"))
        assertTrue(detector.isChallenge(503, "", null, serverHeader = "cloudflare"))
        assertTrue(detector.isChallenge(429, "", null, cfMitigatedHeader = "challenge"))
    }

    @Test
    fun customOptionsReplaceDefaults() {
        val custom = CfOptions(statuses = listOf(418), markers = emptyList())
        // A custom status still needs marker corroboration (markers are empty
        // here, so even 418 is not a challenge on its own).
        assertFalse(detector.isChallenge(418, "", custom))
        assertTrue(detector.isChallenge(418, "cf-challenge", CfOptions(statuses = listOf(418))))
        // 403 is not in custom statuses -> not a challenge without markers.
        assertFalse(detector.isChallenge(403, "", custom))
    }

    // whitelist = true skips detection entirely (NoveLA: whitelisted domains
    // return "not a challenge" before any check) — even a textbook challenge
    // response must not trigger the solver.
    @Test
    fun whitelistSkipsDetectionEntirely() {
        val whitelisted = CfOptions(whitelist = true)
        assertFalse(detector.isChallenge(403, "x __cf_chl_ y", whitelisted, serverHeader = "cloudflare"))
        assertFalse(detector.isChallenge(503, "", whitelisted, serverHeader = "cloudflare"))
        assertFalse(detector.isChallenge(200, "", whitelisted, cfMitigatedHeader = "challenge"))
        // Default (false) leaves detection intact.
        assertTrue(detector.isChallenge(403, "x __cf_chl_ y", CfOptions()))
    }

    @Test
    fun ignoreMarkersFilterOutSuppressedMarkers() {
        val ignoring = CfOptions(ignoreMarkers = listOf("__cf_chl_"))
        assertFalse(detector.isChallenge(200, "x __cf_chl_ y", ignoring))
        // A non-ignored marker still fires.
        assertTrue(detector.isChallenge(200, "x __cf_chl_ y cf-challenge", ignoring))
    }

    // Custom trigger markers fire for non-standard WAFs on any status code,
    // even when the server is not identified as CF/DDoS-Guard (NoveLA parity).
    @Test
    fun triggerMarkersFireOnAnyStatusWithoutCfServer() {
        val custom = CfOptions(triggerMarkers = listOf("x-waf-block"))
        assertTrue(detector.isChallenge(200, "blocked by x-waf-block", custom, serverHeader = "nginx"))
        assertTrue(detector.isChallenge(403, "x-waf-block", custom))
        assertFalse(detector.isChallenge(200, "ordinary page", custom, serverHeader = "nginx"))
    }

    @Test
    fun plainServerErrorIsNotChallenge() {
        assertFalse(detector.isChallenge(500, "internal server error", null))
    }

    @Test
    fun emptyBodyWithOkStatusIsNotChallenge() {
        assertFalse(detector.isChallenge(200, "", null))
    }

    @Test
    fun cfMitigatedChallengeHeaderIsChallenge() {
        assertTrue(detector.isChallenge(200, "", null, cfMitigatedHeader = "challenge"))
        assertFalse(detector.isChallenge(200, "", null, cfMitigatedHeader = "passed"))
    }

    @Test
    fun cloudflareServerHeaderWithChallengeStatusIsChallenge() {
        // Custom options exclude 403 from statuses: the CF edge header still catches it.
        val custom = CfOptions(statuses = emptyList(), markers = emptyList())
        assertTrue(detector.isChallenge(403, "", custom, serverHeader = "cloudflare"))
        assertTrue(detector.isChallenge(503, "", custom, serverHeader = "cloudflare"))
        // A 200 from a CF-fronted site is NOT a challenge on the header alone.
        assertFalse(detector.isChallenge(200, "", custom, serverHeader = "cloudflare"))
        assertFalse(detector.isChallenge(403, "", custom, serverHeader = "nginx"))
    }

    @Test
    fun noveLaMarkerSetIsDetected() {
        assertTrue(detector.isChallenge(200, "x __cf_chl_ y", null))
        assertTrue(detector.isChallenge(200, "cf-error-details", null))
        assertTrue(detector.isChallenge(200, "Attention Required! | Cloudflare", null))
    }

    // NoveLA STATIC_EXTENSIONS shortcut: a challenge-looking body on a static
    // asset path is the origin's error body forwarded by a CDN/proxy, never a
    // solvable challenge.
    @Test
    fun staticAssetPathSkipsDetection() {
        val challengeBody = "x __cf_chl_ y"
        assertFalse(detector.isChallenge(403, challengeBody, null, requestUrl = "https://cdn.example/cover.png"))
        assertFalse(detector.isChallenge(503, challengeBody, null, requestUrl = "https://x.example/app.js?ver=2"))
        assertFalse(detector.isChallenge(403, challengeBody, null, requestUrl = "https://x.example/data.json"))
        assertFalse(
            detector.isChallenge(
                403, challengeBody, null,
                serverHeader = "cloudflare", requestUrl = "https://x.example/font.woff2",
            ),
        )
        // Unknown extension and extensionless paths still detect.
        assertTrue(detector.isChallenge(403, challengeBody, null, requestUrl = "https://x.example/page"))
        assertTrue(detector.isChallenge(403, challengeBody, null, requestUrl = "https://x.example/reader"))
        // No URL (unknown) cannot skip — normal detection applies.
        assertTrue(detector.isChallenge(403, challengeBody, null))
    }

    // NoveLA CLOUDFLARE_WHITELIST: github/raw (infrastructure) and
    // weserv/wsrv (image proxies forwarding the origin body as-is).
    @Test
    fun builtinWhitelistedHostsSkipDetection() {
        val challengeBody = "x __cf_chl_ y"
        assertFalse(
            detector.isChallenge(
                403, challengeBody, null,
                serverHeader = "cloudflare",
                requestUrl = "https://raw.githubusercontent.com/org/repo/file.lua",
            ),
        )
        assertFalse(
            detector.isChallenge(
                503, challengeBody, null,
                requestUrl = "https://images.weserv.nl/?url=cdn.example/cover.jpg",
            ),
        )
        assertFalse(detector.isChallenge(403, challengeBody, null, requestUrl = "https://wsrv.nl/?url=x"))
        assertFalse(detector.isChallenge(403, challengeBody, null, requestUrl = "https://github.com/org/repo"))
        // Substring match like NoveLA's `host.contains(it)`.
        assertFalse(detector.isChallenge(403, challengeBody, null, requestUrl = "https://api.github.com/repos"))
        // A non-listed host still detects.
        assertTrue(detector.isChallenge(403, challengeBody, null, requestUrl = "https://cdn.example/cover"))
    }

    // NoveLA IP_BLOCKED_URL_MARKERS (/cdn-cgi/error/, 1020/1015): a hard IP
    // ban needs no marker corroboration — it must surface as a challenge so
    // the failure path runs instead of a silent success.
    @Test
    fun ipBlockedUrlIsChallengeWithoutMarkers() {
        assertTrue(detector.isChallenge(403, "plain error page", null, requestUrl = "https://x.example/cdn-cgi/error/?e=1"))
        assertTrue(detector.isChallenge(200, "ok", null, requestUrl = "https://x.example/?error=1020"))
        assertTrue(detector.isChallenge(200, "ok", null, requestUrl = "https://x.example/?error=1015"))
        assertFalse(detector.isChallenge(200, "ok", null, requestUrl = "https://x.example/page"))
    }
}
