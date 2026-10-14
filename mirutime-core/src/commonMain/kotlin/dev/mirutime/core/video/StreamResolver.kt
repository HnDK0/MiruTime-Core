package dev.mirutime.core.video

import dev.mirutime.core.api.VideoStream

// Stream selection helpers for the host — pure Kotlin port of the Lua
// helper lib `external-sources/libs/helpers/hls.lua` (loaded in plugins via
// require_lib("hls"); this copy exists because commonMain does not see LuaJ).
//
// pickStream port note: the ethalon scans page TEXT for the FIRST .m3u8/.mp4
// hit in scan order — it has no quality ranking. Over a resolved
// List<VideoStream> the same semantics are: exact preferQuality match wins,
// otherwise the first element (list order == ethalon scan order). The
// ethalon's numeric "closest quality" heuristic does not exist — none added.

// Ethalon union from hls.lua hasMediaExt (anime4up/animephoenix/witanime
// plugin copies): .m3u8/.mp4/.mkv only — .webm deliberately absent.
// Case-sensitive and applied to the path before "?" / "#" — same as the Lua
// `base:match("%.m3u8$")` on `u:match("^[^%?#]*")`.
private val MEDIA_EXTENSIONS = listOf(".m3u8", ".mp4", ".mkv")

// Returns the stream matching preferQuality exactly, else the first element
// (ethalon order semantics); empty list → null.
fun pickStream(streams: List<VideoStream>, preferQuality: String?): VideoStream? {
    if (streams.isEmpty()) return null
    if (preferQuality != null) {
        streams.firstOrNull { it.quality == preferQuality }?.let { return it }
    }
    return streams.first()
}

// True when the URL path (before "?" / "#") ends with a media extension.
fun hasMediaExt(url: String): Boolean {
    val base = url.substringBefore('?').substringBefore('#')
    return MEDIA_EXTENSIONS.any { base.endsWith(it) }
}
