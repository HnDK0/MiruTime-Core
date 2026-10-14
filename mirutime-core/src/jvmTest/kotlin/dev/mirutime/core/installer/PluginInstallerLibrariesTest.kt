package dev.mirutime.core.installer

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.engine.createLuaSession
import dev.mirutime.core.engine.loader.LibraryLoader
import dev.mirutime.core.host.FakeFileSystem
import dev.mirutime.core.network.cache.sha256Hex
import dev.mirutime.core.network.security.SsrfGuard
import kotlinx.coroutines.runBlocking
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.TwoArgFunction
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PluginInstallerLibrariesTest {

    private val fs = FakeFileSystem()
    private val installDir = "plugins"
    private val libDir = "libs"
    private val downloads = mutableMapOf<String, ByteArray>()
    private val installer = PluginInstaller(
        fs, { url -> downloads[url] ?: error("404 $url") }, installDir, libDir,
        // Fake hostnames must skip real DNS; the guard still validates scheme + IPs.
        ssrfGuard = SsrfGuard { listOf("93.184.216.1") },
    )

    private val urls = "https://registry.example/libs/urls.lua"
    private val libCode = "return { version = 1 }".encodeToByteArray()

    private fun libEntry(id: String = "urls", sha256: String? = sha256Hex(libCode), url: String = urls) =
        IndexEntry(id = id, name = null, version = null, url = url, sha256 = sha256)

    @Test
    fun `installLibrary commits into libDir and one-arg require_lib resolves it`() {
        downloads[urls] = libCode

        val result = runBlocking { installer.installLibrary(libEntry()) }

        assertEquals("$libDir/urls.lua", assertIs<InstallResult.Success>(result).path)
        assertContentEquals(libCode, fs.read("$libDir/urls.lua"))

        // The engine's require_lib is 1-arg (NoveLA/corpus form) — it must
        // resolve the freshly installed library from the same libDir.
        val loader = LibraryLoader(fs, libDir)
        val session = createLuaSession("plugin", "return 1")
        loader.install(session)
        val table = (session.globals.get("require_lib") as TwoArgFunction)
            .call(LuaValue.valueOf("urls"))
        assertEquals(1, table.get("version").toint())
    }

    @Test
    fun `installLibraries installs every entry from the index libraries section`() {
        downloads[urls] = libCode
        val index = parsePluginIndex(
            """
            libraries:
              - id: "urls"
                url: "$urls"
                sha256: "${sha256Hex(libCode)}"
            """.trimIndent(),
        )

        val results = runBlocking { installer.installLibraries(index) }

        assertIs<InstallResult.Success>(results.single())
        assertContentEquals(libCode, fs.read("$libDir/urls.lua"))
    }

    @Test
    fun `library id outside the require_lib rule is rejected`() {
        for (bad in listOf("BadId", "sub/dir", "../evil")) {
            val result = runBlocking { installer.installLibrary(libEntry(id = bad)) }
            assertContains(assertIs<InstallResult.Failed>(result).reason, "invalid library id")
        }
    }

    @Test
    fun `library sha mismatch writes nothing`() {
        downloads[urls] = libCode
        val result = runBlocking { installer.installLibrary(libEntry(sha256 = "0".repeat(64))) }
        assertContains(assertIs<InstallResult.Failed>(result).reason, "sha256 mismatch")
        assertEquals(false, fs.exists("$libDir/urls.lua"))
    }

    @Test
    fun `installLibrary rejects hostile url before download when guard present`() {
        // Default guard: literal IPs never touch DNS, so no network is needed.
        val guarded = PluginInstaller(fs, { url -> downloads[url] ?: error("404 $url") }, installDir, libDir)
        val result = runBlocking {
            guarded.installLibrary(libEntry(url = "http://169.254.169.254/urls.lua"))
        }
        assertContains(assertIs<InstallResult.Failed>(result).reason, "SSRF blocked")
    }

    @Test
    fun `source install attaches metadata parsed from the script header`() {
        val url = "https://registry.example/komiku.lua"
        val code = """
            id = "komiku"
            name = "Komiku"
            content_type = "manga"
            function getCatalogList(index) return { items = {} } end
        """.trimIndent().encodeToByteArray()
        downloads[url] = code

        val result = runBlocking {
            installer.install(IndexEntry(id = "komiku", name = "Komiku", version = null, url = url, sha256 = sha256Hex(code)))
        }

        val meta = assertIs<InstallResult.Success>(result).metadata!!
        assertEquals("komiku", meta.id)
        assertEquals("manga", meta.contentType)
        assertEquals(ContentType.MANGA, meta.toPluginMetadata("komiku", 1, sha256Hex(code)).contentType)
    }
}
