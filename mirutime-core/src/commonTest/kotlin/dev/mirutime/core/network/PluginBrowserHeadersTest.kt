package dev.mirutime.core.network

import dev.mirutime.core.host.FakeSystemInfo
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// NoveLA parity: browser header set + UA preset resolution.
class PluginBrowserHeadersTest {

    private val systemInfo = FakeSystemInfo(defaultUserAgent = "MiruCore/1.0", acceptLanguage = "ru-RU")

    private fun headers(
        preset: String? = null,
        extra: Map<String, String> = emptyMap(),
        url: String = "https://example.com/page",
    ) = HttpRequestBuilder().also { it.url(url) }
        .let { builder ->
            PluginRequestBuilder(systemInfo, preset).applyTo(builder, extra)
            builder.headers
        }

    // ── presets ──────────────────────────────────────────────────────────────

    @Test
    fun `preset names and aliases resolve to full browser UA`() {
        assertEquals(UserAgentPresets.CHROME_150_WINDOWS, UserAgentPresets.resolve("Chrome 150 (Windows)"))
        assertEquals(UserAgentPresets.CHROME_150_ANDROID, UserAgentPresets.resolve("Chrome Mobile"))
        assertEquals(UserAgentPresets.SAFARI_18_IOS, UserAgentPresets.resolve("Safari Mobile"))
        assertEquals(9, UserAgentPresets.allPresets().size)
        assertNull(UserAgentPresets.resolve("Unknown"))
    }

    @Test
    fun `plugin preset name becomes full browser UA`() {
        val h = headers(preset = "Chrome Mobile")
        assertEquals(UserAgentPresets.CHROME_150_ANDROID, h[HttpHeaders.UserAgent])
    }

    @Test
    fun `unknown preset value is used literally and null falls back to browser default`() {
        PluginUserAgentRegistry.clear()
        assertEquals("CustomUA/1.0", headers(preset = "CustomUA/1.0")[HttpHeaders.UserAgent])
        assertEquals(UserAgentPresets.DEFAULT, headers(preset = null)[HttpHeaders.UserAgent])
    }

    @Test
    fun `no metadata preset falls back to per-host registry then browser default`() {
        PluginUserAgentRegistry.clear()
        assertEquals(UserAgentPresets.DEFAULT, headers(preset = null)[HttpHeaders.UserAgent])
        PluginUserAgentRegistry.registerHost("example.com", "Chrome Mobile")
        assertEquals(UserAgentPresets.CHROME_150_ANDROID, headers(preset = null)[HttpHeaders.UserAgent])
        // A configured metadata preset wins over the host registry (parity order).
        assertEquals(
            UserAgentPresets.SAFARI_18_IOS,
            headers(preset = "Safari Mobile")[HttpHeaders.UserAgent],
        )
        PluginUserAgentRegistry.clear()
    }

    // ── full Chrome header set ───────────────────────────────────────────────

    @Test
    fun `chromium navigation carries full chrome header set`() {
        val h = headers(preset = "Chrome 150 (Windows)")
        // sec-ch-ua: a GREASE brand must sit next to the real brands.
        assertTrue(h["sec-ch-ua"]!!.contains("Chromium"))
        assertTrue(h["sec-ch-ua"]!!.contains("Google Chrome"))
        assertTrue(h["sec-ch-ua"]!!.startsWith("\"Not"))
        assertEquals("?0", h["sec-ch-ua-mobile"])
        assertEquals("\"Windows\"", h["sec-ch-ua-platform"])
        assertEquals("document", h["sec-fetch-dest"])
        assertEquals("navigate", h["sec-fetch-mode"])
        assertEquals("same-origin", h["sec-fetch-site"])
        assertEquals("1", h["upgrade-insecure-requests"])
        assertEquals("u=0, i", h["priority"])
        assertEquals(PluginRequestBuilder.NAV_ACCEPT, h[HttpHeaders.Accept])
        assertEquals("gzip, deflate, br", h[HttpHeaders.AcceptEncoding])
        assertEquals("https://example.com/", h["Referer"])
        assertEquals("ru-RU", h[HttpHeaders.AcceptLanguage])
    }

    @Test
    fun `non chromium ua skips chromium-only headers but keeps referer and accept`() {
        val h = headers(preset = "Safari Mobile")
        assertNull(h["sec-ch-ua"])
        assertNull(h["priority"])
        assertNull(h["upgrade-insecure-requests"])
        assertEquals("https://example.com/", h["Referer"])
        assertEquals(PluginRequestBuilder.NAV_ACCEPT, h[HttpHeaders.Accept])
    }

    // ── per-kind selection ───────────────────────────────────────────────────

    @Test
    fun `image accept selects image kind`() {
        val h = headers(preset = "Chrome 150 (Windows)", extra = mapOf("Accept" to "image/webp,image/*"))
        assertEquals("image", h["sec-fetch-dest"])
        assertEquals("no-cors", h["sec-fetch-mode"])
        // caller Accept wins over the IMG_ACCEPT default
        assertEquals("image/webp,image/*", h[HttpHeaders.Accept])
    }

    @Test
    fun `origin selects fetch kind and cross-site`() {
        val h = headers(
            preset = "Chrome 150 (Windows)",
            extra = mapOf("Origin" to "https://other.example"),
        )
        assertEquals("empty", h["sec-fetch-dest"])
        assertEquals("cors", h["sec-fetch-mode"])
        assertEquals("cross-site", h["sec-fetch-site"])
        assertNull(h["priority"])
    }

    // ── caller wins ──────────────────────────────────────────────────────────

    @Test
    fun `caller headers override injected defaults`() {
        val h = headers(
            preset = "Chrome 150 (Windows)",
            extra = mapOf(
                "User-Agent" to "CallerUA/9.9",
                "Referer" to "https://caller.example/book/1",
                "Accept" to "application/json",
            ),
        )
        assertEquals("CallerUA/9.9", h[HttpHeaders.UserAgent])
        assertEquals("https://caller.example/book/1", h["Referer"])
        assertEquals("application/json", h[HttpHeaders.Accept])
    }

    // ── registry ─────────────────────────────────────────────────────────────

    @Test
    fun `registry resolves source then host and defaults to browser UA`() {
        PluginUserAgentRegistry.clear()
        PluginUserAgentRegistry.register("plugin.a", "Safari Mobile")
        PluginUserAgentRegistry.registerHost("example.com", "Chrome Mobile")

        assertEquals(UserAgentPresets.SAFARI_18_IOS, PluginUserAgentRegistry.resolve("plugin.a", "other.com"))
        assertEquals(UserAgentPresets.CHROME_150_ANDROID, PluginUserAgentRegistry.resolve(null, "sub.example.com"))
        assertEquals(UserAgentPresets.DEFAULT, PluginUserAgentRegistry.resolve(null, "unregistered.com"))
        PluginUserAgentRegistry.clear()
    }

    @Test
    fun `host options registry stores and clears per-host referer`() {
        val options = HostOptionsRegistry()
        options.setReferer("example.com", "plugin.a", "https://example.com/")
        assertEquals("https://example.com/", options.referer("example.com"))
        options.clear("example.com", "plugin.b")
        assertEquals("https://example.com/", options.referer("example.com"))
        options.clear("example.com", "plugin.a")
        assertNull(options.referer("example.com"))
    }

    // ── per-host referer ─────────────────────────────────────────────────────

    private fun headersWithReferer(
        refererOverride: String? = null,
        extra: Map<String, String> = emptyMap(),
        url: String = "https://example.com/page",
    ) = HttpRequestBuilder().also { it.url(url) }
        .let { builder ->
            PluginRequestBuilder(systemInfo, null).applyTo(builder, extra, refererOverride = refererOverride)
            builder.headers
        }

    @Test
    fun `registry referer applies when the caller sent none`() {
        assertEquals("https://covers.example/", headersWithReferer("https://covers.example/")["Referer"])
    }

    @Test
    fun `explicit caller referer wins over the registry override`() {
        val h = headersWithReferer(
            refererOverride = "https://covers.example/",
            extra = mapOf("Referer" to "https://caller.example/book/1"),
        )
        assertEquals("https://caller.example/book/1", h["Referer"])
    }

    @Test
    fun `blank registry referer falls back to the scheme-host default`() {
        assertEquals("https://example.com/", headersWithReferer("  ")["Referer"])
    }

    @Test
    fun `no override keeps the scheme-host default`() {
        assertEquals("https://example.com/", headersWithReferer()["Referer"])
    }

}
