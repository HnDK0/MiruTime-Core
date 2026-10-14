package dev.mirutime.core.network

// One observed HTTP attempt inside PluginHttpClient — emitted AFTER the body is
// read, so bodyBytes is the real payload size. Observers are diagnostics only
// (the plugin-tester NETWORK TRACE section); a null trace must cost nothing, so
// PluginHttpClient skips every field computation, including the CF detection
// pass, when no sink is wired.
//
// redirectHop is 0-based within one redirect walk: a post-CF-solver retry
// restarts at 0, so the sequence 0,1,2 always describes the hops of the call
// currently being retried rather than a run-wide counter.
// cfBlocked means CloudflareDetector flagged THIS response as a challenge —
// the solver retry that follows may well succeed.
// Hosts must not log requestHeaders verbatim: they carry the full Cookie.
data class NetworkTraceEvent(
    val method: String,
    val url: String,
    val status: Int,
    // Headers actually sent on the wire (UA, Cookie, Accept-*), not the
    // caller's intent. Multi-valued request headers are joined with ", ".
    // Response header names are LOWERCASED — the same shape NetworkBindings
    // hands to plugins — so a consumer reads one key style for both.
    val requestHeaders: Map<String, String>,
    val responseHeaders: Map<String, List<String>>,
    val bodyBytes: Long,
    val durationMs: Long,
    val cfBlocked: Boolean,
    val redirectHop: Int,
)
