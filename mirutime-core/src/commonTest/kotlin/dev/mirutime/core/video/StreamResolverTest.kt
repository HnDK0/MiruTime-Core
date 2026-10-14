package dev.mirutime.core.video

import dev.mirutime.core.api.VideoStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StreamResolverTest {

    private fun stream(url: String, quality: String? = null) =
        VideoStream(url = url, quality = quality)

    @Test
    fun `empty list returns null`() {
        assertNull(pickStream(emptyList(), "1080p"))
        assertNull(pickStream(emptyList(), null))
    }

    @Test
    fun `prefers exact quality match`() {
        val a = stream("https://a/720.m3u8", "720p")
        val target = stream("https://b/1080.m3u8", "1080p")
        val c = stream("https://c/480.m3u8", "480p")
        assertSame(target, pickStream(listOf(a, target, c), "1080p"))
    }

    @Test
    fun `exact match wins over list order`() {
        val first = stream("https://first/360.mp4", "360p")
        val exact = stream("https://exact/720.mp4", "720p")
        assertSame(exact, pickStream(listOf(first, exact), "720p"))
    }

    @Test
    fun `falls back to first element without exact match`() {
        val a = stream("https://a.m3u8", "720p")
        val b = stream("https://b.m3u8", "360p")
        // Ethalon order semantics: hls.lua returns the FIRST scan hit.
        assertSame(a, pickStream(listOf(a, b), "1080p"))
        assertSame(a, pickStream(listOf(a, b), null))
    }

    @Test
    fun `hasMediaExt recognizes ethalon extensions`() {
        assertTrue(hasMediaExt("https://cdn.example/v.m3u8"))
        assertTrue(hasMediaExt("https://cdn.example/v.mp4?token=1#frag"))
        assertTrue(hasMediaExt("https://cdn.example/file.mkv"))
    }

    @Test
    fun `hasMediaExt rejects non media urls`() {
        assertFalse(hasMediaExt("https://cdn.example/watch.php"))
        assertFalse(hasMediaExt("https://cdn.example/page.html"))
        // .webm is NOT part of the ethalon union (hls.lua: m3u8/mp4/mkv only).
        assertFalse(hasMediaExt("https://cdn.example/v.webm"))
    }
}
