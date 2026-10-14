package dev.mirutime.core.installer

import dev.mirutime.core.api.SUPPORTED_PLUGIN_API
import dev.mirutime.core.host.FakeFileSystem
import dev.mirutime.core.network.cache.sha256Hex
import dev.mirutime.core.network.security.SsrfGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginInstallerTest {

    private val fs = FakeFileSystem()
    private val dir = "plugins"
    private val downloads = mutableMapOf<String, ByteArray>()
    // Fake hostnames must skip real DNS (LuaPluginSourceFactory precedent:
    // tests inject the resolver); the guard still validates scheme + IPs.
    private val publicIpGuard = SsrfGuard { listOf("93.184.216.1") }
    private val installer = PluginInstaller(fs, { url -> downloads[url] ?: error("404 $url") }, dir, ssrfGuard = publicIpGuard)

    private val url = "https://registry.example/demo.lua"

    private fun script(text: String) = text.encodeToByteArray()

    private fun entry(sha256: String?, version: String? = "1.0.0", api: Int? = null) = IndexEntry(
        id = "demo",
        name = "Demo",
        version = version,
        url = url,
        sha256 = sha256,
        api = api,
    )

    @Test
    fun `valid entry installs atomically`() {
        val code = script("function getCatalogList(index) return {items = {}} end")
        downloads[url] = code

        val result = runBlocking { installer.install(entry(sha256Hex(code))) }

        assertEquals("$dir/demo.lua", assertIs<InstallResult.Success>(result).path)
        assertContentEquals(code, fs.read("$dir/demo.lua"))
        // No temp leftovers: commit is write-tmp + rename.
        assertNull(fs.read("$dir/demo.lua.tmp"))
        assertFalse(installer.isUpdateAvailable(entry(sha256Hex(code))))
    }

    @Test
    fun `sha mismatch fails and keeps the previous file`() {
        val old = script("x = 1 -- previous installed version")
        fs.write("$dir/demo.lua", old)
        downloads[url] = script("x = 2 -- new download")

        val result = runBlocking { installer.install(entry("0".repeat(64))) }

        assertContains(assertIs<InstallResult.Failed>(result).reason, "sha256 mismatch")
        // Rollback: nothing was written — the installed file is untouched.
        assertContentEquals(old, fs.read("$dir/demo.lua"))
    }

    @Test
    fun `invalid syntax fails and writes nothing`() {
        val bad = script("function broken( {{{")
        downloads[url] = bad

        val result = runBlocking { installer.install(entry(sha256Hex(bad))) }

        assertIs<InstallResult.Failed>(result)
        assertNull(fs.read("$dir/demo.lua"))
    }

    @Test
    fun `entry without sha256 installs (syntax check + atomic commit)`() {
        val code = script("function getCatalogList(index) return {items = {}} end")
        downloads[url] = code
        val result = runBlocking { installer.install(entry(sha256 = null)) }
        assertIs<InstallResult.Success>(result)
        assertEquals("$dir/demo.lua", result.path)
        assertNull(result.sha256)
        assertContentEquals(code, fs.read("$dir/demo.lua"))
    }

    @Test
    fun `entry with unsupported api is rejected before download`() {
        // downloads has NO entry: the api check must run before the fetch.
        val result = runBlocking {
            installer.install(entry(sha256 = "0".repeat(64), api = SUPPORTED_PLUGIN_API + 1))
        }
        assertContains(assertIs<InstallResult.Failed>(result).reason, "unsupported api")
        assertNull(fs.read("$dir/demo.lua"))
    }

    @Test
    fun `entry with supported or absent api proceeds`() {
        val code = script("function getCatalogList(index) return {items = {}} end")
        downloads[url] = code
        val sha = sha256Hex(code)
        assertIs<InstallResult.Success>(
            runBlocking { installer.install(entry(sha256 = sha, api = SUPPORTED_PLUGIN_API)) },
        )
        assertIs<InstallResult.Success>(
            runBlocking { installer.install(entry(sha256 = sha, api = null)) },
        )
    }

    @Test
    fun `update decision is sha based not version`() {
        val code = script("x = 1")
        fs.write("$dir/demo.lua", code)

        // Same sha with an absurd version — no update.
        assertFalse(installer.isUpdateAvailable(entry(sha256Hex(code), version = "999.999.999")))
        // Different sha at the same version — update.
        assertTrue(installer.isUpdateAvailable(entry(sha256Hex(script("x = 2")))))
        // Not installed at all — treated as an install candidate.
        assertTrue(
            installer.isUpdateAvailable(entry(sha256Hex(code)).copy(id = "other")),
        )
    }

    // ── Fix round 1 ──────────────────────────────────────────────────────────

    @Test
    fun `hostile id is rejected before download and writes nothing`() {
        // downloads has NO entry: if validation did not run first, the
        // fetcher would fail and the reason would mention "download failed"
        // instead of "invalid id".
        for (badId in listOf("../evil", "sub/dir", "..", "a..b")) {
            val result = runBlocking { installer.install(entry(sha256Hex(script("x = 1"))).copy(id = badId)) }
            assertContains(assertIs<InstallResult.Failed>(result).reason, "invalid id")
        }
        assertTrue(fs.files.isEmpty())
    }

    @Test
    fun `real catalog ids pass validation`() {
        // Verified against external-sources language indexes: "mvlempyr.com"
        // (dot) and "NovelArrow" (uppercase) are shipped ids.
        val code = script("x = 1")
        downloads[url] = code
        val sha = sha256Hex(code)
        for (realId in listOf("mvlempyr.com", "NovelArrow")) {
            val result = runBlocking { installer.install(entry(sha).copy(id = realId)) }
            assertEquals("$dir/$realId.lua", assertIs<InstallResult.Success>(result).path)
            assertContentEquals(code, fs.read("$dir/$realId.lua"))
        }
    }

    @Test
    fun `cancellation from the fetcher propagates`() {
        // Guard off so the fetcher is actually reached.
        val cancelling = PluginInstaller(fs, { throw CancellationException("cancelled") }, dir, ssrfGuard = null)
        assertFailsWith<CancellationException> {
            runBlocking { cancelling.install(entry("0".repeat(64))) }
        }
    }

    // ── SSRF guard ─────────────────────────────────────────────────────────

    @Test
    fun `hostile index url is rejected before download when guard present`() {
        // Default guard (same default as LuaPluginSourceFactory): literal IPs
        // never touch DNS, so refusing them needs no network.
        val guarded = PluginInstaller(fs, { url -> downloads[url] ?: error("404 $url") }, dir)
        for (hostile in listOf("http://169.254.169.254/demo.lua", "http://localhost:8080/demo.lua")) {
            val result = runBlocking {
                guarded.install(entry(sha256Hex(script("x = 1"))).copy(url = hostile))
            }
            assertContains(assertIs<InstallResult.Failed>(result).reason, "SSRF blocked")
        }
        assertTrue(fs.files.isEmpty())
    }

    @Test
    fun `unresolvable index host fails closed when guard present`() {
        val noDns = PluginInstaller(
            fs, { url -> downloads[url] ?: error("404 $url") }, dir,
            ssrfGuard = SsrfGuard { emptyList() },
        )
        val result = runBlocking { noDns.install(entry(sha256Hex(script("x = 1")))) }
        assertContains(assertIs<InstallResult.Failed>(result).reason, "DNS resolution failed")
        assertTrue(fs.files.isEmpty())
    }

    @Test
    fun `hostile url installs when guard is null (check off)`() {
        val unguarded = PluginInstaller(fs, { url -> downloads[url] ?: error("404 $url") }, dir, ssrfGuard = null)
        val hostile = "http://169.254.169.254/demo.lua"
        val code = script("x = 1")
        downloads[hostile] = code
        val result = runBlocking { unguarded.install(entry(sha256Hex(code)).copy(url = hostile)) }
        assertIs<InstallResult.Success>(result)
        assertContentEquals(code, fs.read("$dir/demo.lua"))
    }
}
