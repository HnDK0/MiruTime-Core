package dev.mirutime.core.api

import dev.mirutime.core.network.HostOptionsRegistry
import dev.mirutime.core.network.cf.CloudflareDetector
import dev.mirutime.core.network.cf.CfOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginRegistryTest {

    private val hostOptions = HostOptionsRegistry()
    private val cfOptions = CfOptions(statuses = listOf(418), markers = listOf("Teapot"))

    private fun descriptor(
        id: String = "dev.mirutime.demo",
        baseUrl: String = "https://site.example/path",
        cf: CfOptions? = null,
        ua: String? = null,
        api: Int = 1,
    ) = PluginDescriptor(
        metadata = PluginMetadata(
            id = id,
            name = "Demo",
            baseUrl = baseUrl,
            contentType = ContentType.NOVEL,
            language = "en",
            api = api,
            version = "0.1.0",
            sha256 = "abc",
            userAgentPreset = ua,
            cfOptions = cf,
        ),
        script = "return {}",
    )

    private fun fakeSource(meta: PluginMetadata): PluginSource = object : PluginSource {
        override val metadata: PluginMetadata = meta
        override suspend fun catalog(index: Int): Outcome<CatalogPage> =
            Outcome.Ok(CatalogPage(emptyList(), hasNext = false))
        override suspend fun search(index: Int, query: String): Outcome<CatalogPage> =
            Outcome.Ok(CatalogPage(emptyList(), hasNext = false))
        override suspend fun catalogFiltered(
            index: Int,
            filters: Map<String, List<String>>,
        ): Outcome<CatalogPage> = Outcome.Ok(CatalogPage(emptyList(), hasNext = false))
        override suspend fun book(url: String): Outcome<BookMeta> = Outcome.Ok(BookMeta("T"))
        override suspend fun chapters(url: String): Outcome<List<Chapter>> =
            Outcome.Ok(emptyList())
        override suspend fun chapterContent(html: String, url: String): Outcome<ChapterContent> =
            Outcome.Ok(ChapterContent.Text("body"))
        override suspend fun filters(): Outcome<List<Map<String, String>>> = Outcome.Ok(emptyList())
        override suspend fun settingsSchema(): Outcome<List<Map<String, String>>> =
            Outcome.Ok(emptyList())
    }

    private fun registry(
        factory: PluginSourceFactory = PluginSourceFactory { desc -> fakeSource(desc.metadata) },
    ) = PluginRegistry(factory, hostOptions)

    // ── state transitions ────────────────────────────────────────────────────

    @Test
    fun `successful install reports loaded state`() {
        val handle = registry().install(descriptor())
        assertEquals(PluginState.Loaded, handle.state.value)
        assertNotNull(handle.source)
        assertEquals("dev.mirutime.demo", handle.source!!.metadata.id)
    }

    @Test
    fun `failed install is visible in state`() {
        val handle = registry(PluginSourceFactory { error("boom") }).install(descriptor())
        val failed = handle.state.value
        assertTrue(failed is PluginState.Failed, "expected Failed, got $failed")
        assertEquals("dev.mirutime.demo", (failed as PluginState.Failed).pluginId)
        assertEquals("boom", failed.reason)
        assertNull(handle.source)
    }

    @Test
    fun `install with unsupported api fails with visible UnsupportedApi`() {
        val handle = registry().install(descriptor(api = SUPPORTED_PLUGIN_API + 1))
        val failed = handle.state.value
        assertTrue(failed is PluginState.Failed, "expected Failed, got $failed")
        val error = kotlin.test.assertIs<PluginError.UnsupportedApi>((failed as PluginState.Failed).error)
        assertEquals(SUPPORTED_PLUGIN_API + 1, error.required)
        assertEquals(SUPPORTED_PLUGIN_API, error.actual)
        assertNull(handle.source)
    }

    @Test
    fun `install with supported api proceeds`() {
        assertEquals(PluginState.Loaded, registry().install(descriptor(api = SUPPORTED_PLUGIN_API)).state.value)
    }

    @Test
    fun `failed install registers nothing in host mapping`() {
        registry(PluginSourceFactory { error("boom") })
            .install(descriptor(cf = cfOptions, ua = "CustomUA/1.0"))
        assertNull(hostOptions.cfOptions("site.example"))
        assertNull(hostOptions.userAgent("site.example"))
    }

    // ── CF/UA host mapping lifecycle ─────────────────────────────────────────

    @Test
    fun `dispose removes cf options from host mapping`() {
        val handle = registry().install(descriptor(cf = cfOptions))
        assertEquals(cfOptions, hostOptions.cfOptions("site.example"))

        // Observable through the real detector: a Teapot body is a challenge
        // while the plugin's options are registered…
        val detector = CloudflareDetector()
        assertTrue(
            detector.isChallenge(200, "Teapot says hi", hostOptions.cfOptions("site.example")),
        )

        handle.dispose()

        // …and stops being one after dispose clears the host entry.
        assertNull(hostOptions.cfOptions("site.example"))
        assertFalse(
            detector.isChallenge(200, "Teapot says hi", hostOptions.cfOptions("site.example")),
        )
    }

    @Test
    fun `user agent preset registered on install and cleared on dispose`() {
        val handle = registry().install(descriptor(ua = "CustomUA/1.0"))
        assertEquals("CustomUA/1.0", hostOptions.userAgent("site.example"))
        handle.dispose()
        assertNull(hostOptions.userAgent("site.example"))
    }

    @Test
    fun `host mapping keys on normalized host without www, port or path`() {
        val handle = registry().install(
            descriptor(baseUrl = "https://www.Site.Example:8080/x", cf = cfOptions),
        )
        assertEquals(cfOptions, hostOptions.cfOptions("site.example"))
        handle.dispose()
        assertNull(hostOptions.cfOptions("site.example"))
    }

    // ── owner-scoped cleanup ─────────────────────────────────────────────────

    @Test
    fun `disposing a failed plugin does not touch another plugin options on the same host`() {
        // Plugin A is Loaded on site.example with its own CF/UA registrations.
        val a = registry().install(descriptor(id = "plugin.a", cf = cfOptions, ua = "A-UA/1.0"))
        assertEquals(cfOptions, hostOptions.cfOptions("site.example"))

        // Plugin B (same host) fails to install, then gets disposed.
        val b = registry(PluginSourceFactory { error("boom") })
            .install(descriptor(id = "plugin.b", cf = cfOptions, ua = "B-UA/1.0"))
        b.dispose()

        // A is still Loaded — its registrations must survive B's dispose.
        assertEquals(cfOptions, hostOptions.cfOptions("site.example"))
        assertEquals("A-UA/1.0", hostOptions.userAgent("site.example"))
        assertFalse(a.isDisposed)
    }

    // ── dispose semantics ────────────────────────────────────────────────────

    @Test
    fun `dispose marks handle disposed and is idempotent`() {
        val handle = registry().install(descriptor())
        assertFalse(handle.isDisposed)
        handle.dispose()
        handle.dispose()
        assertTrue(handle.isDisposed)
    }
}
