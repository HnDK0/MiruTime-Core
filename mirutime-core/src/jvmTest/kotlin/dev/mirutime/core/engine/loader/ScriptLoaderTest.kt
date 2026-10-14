package dev.mirutime.core.engine.loader

import dev.mirutime.core.engine.createSandboxGlobals
import dev.mirutime.core.host.FakeFileSystem
import dev.mirutime.core.network.cache.sha256Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ScriptLoaderTest {

    private val fs = FakeFileSystem()
    private val script = "function greet() return 'hi' end\nreturn greet\n"
    private val scriptBytes = script.encodeToByteArray()
    private val sha = sha256Hex(scriptBytes)

    private fun loader(fetcher: suspend (String) -> ByteArray = { error("no fetch") }) =
        ScriptLoader(fs, fetcher)

    private fun bytecodePath(sha256: String) = "lua_bytecode/$sha256-3.0.1.lbc"

    // .lbc body = 64-byte sha256 header + bytecode payload.
    private val headerBytes = 64
    private fun ByteArray.payload(): ByteArray = copyOfRange(headerBytes, size)

    // ── sha256 validation: commit vs rollback ────────────────────────────────

    @Test
    fun `valid sha commits script and bytecode cache`() {
        fs.write("src/plugin.lua", scriptBytes)
        val loaded = loader().load("src/plugin.lua", sha)

        assertArrayEquals(scriptBytes, fs.read("lua_scripts/plugin.lua"))
        assertArrayEquals(scriptBytes, loaded.code.encodeToByteArray())
        assertEquals(sha, loaded.sha256)
        // Bytecode cache committed and loadable (dump/load round-trip),
        // its payload bound to the verified source via the sha header.
        val cached = fs.read(bytecodePath(sha))
        assertTrue(".lbc cache must exist", cached != null)
        assertArrayEquals(loaded.bytecode, cached!!.payload())
        // No temp files left behind by the atomic write.
        assertTrue(fs.files.keys.none { it.endsWith(".tmp") })
    }

    @Test
    fun `wrong sha rolls back — nothing is committed`() {
        fs.write("src/plugin.lua", scriptBytes)
        val wrongSha = sha256Hex("tampered content".encodeToByteArray())
        assertThrows(ScriptLoadException::class.java) {
            loader().load("src/plugin.lua", wrongSha)
        }
        assertFalse(fs.exists("lua_scripts/plugin.lua"))
        assertFalse(fs.exists(bytecodePath(wrongSha)))
        assertFalse(fs.exists(bytecodePath(sha)))
        assertTrue(fs.files.keys.none { it.endsWith(".tmp") })
    }

    @Test
    fun `sha mismatch keeps previously committed script untouched`() {
        fs.write("src/plugin.lua", scriptBytes)
        loader().load("src/plugin.lua", sha)
        // Source replaced on disk with tampered content; the caller still
        // pins the OLD sha → mismatch → the committed v1 file survives.
        val tampered = "return 'evil'".encodeToByteArray()
        fs.write("src/plugin.lua", tampered)
        assertThrows(ScriptLoadException::class.java) {
            loader().load("src/plugin.lua", sha)
        }
        assertArrayEquals(scriptBytes, fs.read("lua_scripts/plugin.lua"))
    }

    // ── bytecode cache: invalidation and corruption ──────────────────────────

    @Test
    fun `sha change recompiles to a new cache entry`() {
        fs.write("src/plugin.lua", scriptBytes)
        loader().load("src/plugin.lua", sha)

        val v2 = "return 'v2'"
        val v2Bytes = v2.encodeToByteArray()
        val v2Sha = sha256Hex(v2Bytes)
        fs.write("src/plugin.lua", v2Bytes)
        val loaded = loader().load("src/plugin.lua", v2Sha)

        // Content-addressed: the new sha gets its own .lbc; the old stays.
        assertTrue(fs.exists(bytecodePath(v2Sha)))
        assertTrue(fs.exists(bytecodePath(sha)))
        assertArrayEquals(loaded.bytecode, fs.read(bytecodePath(v2Sha))!!.payload())
    }

    @Test
    fun `foreign bytecode without matching sha header is recompiled`() {
        fs.write("src/plugin.lua", scriptBytes)
        val first = loader().load("src/plugin.lua", sha)
        // Swap in valid bytecode compiled from a DIFFERENT script at our
        // cache path — provenance must be checked, not just loadability.
        val foreign = compileLuaBytecode(createSandboxGlobals(), "return 'foreign'", "evil")
        fs.write(bytecodePath(sha), foreign)

        val second = loader().load("src/plugin.lua", sha)
        assertArrayEquals("must recompile from the verified source", first.bytecode, second.bytecode)
        assertFalse(fs.read(bytecodePath(sha)).contentEquals(foreign))
    }

    @Test
    fun `corrupt bytecode cache is deleted and recompiled`() {
        fs.write("src/plugin.lua", scriptBytes)
        val first = loader().load("src/plugin.lua", sha)
        fs.write(bytecodePath(sha), "garbage".encodeToByteArray())

        val second = loader().load("src/plugin.lua", sha)
        // Recompiled: the cache no longer holds garbage and the bytecode runs.
        assertFalse("garbage must be replaced", fs.read(bytecodePath(sha)).contentEquals("garbage".encodeToByteArray()))
        assertArrayEquals(first.bytecode, second.bytecode)
    }

    // ── sources: local path vs URL ───────────────────────────────────────────

    @Test
    fun `url source is fetched via injected fetcher`() {
        val url = "https://cdn.example/plugin.lua"
        val l = loader(fetcher = { requested ->
            if (requested == url) scriptBytes else error("unexpected url: $requested")
        })
        val loaded = l.load(url, sha)
        assertEquals(sha, loaded.sha256)
        assertArrayEquals(scriptBytes, fs.read("lua_scripts/plugin.lua"))
    }

    @Test
    fun `missing local script fails`() {
        assertThrows(ScriptLoadException::class.java) {
            loader().load("src/absent.lua", sha)
        }
    }

    @Test
    fun `bytecode round-trip executes the script`() {
        fs.write("src/plugin.lua", scriptBytes)
        val loaded = loader().load("src/plugin.lua", sha)
        // Load the dumped bytecode as a chunk and call it: it must return the
        // greet function, proving DumpState/LoadState round-trip fidelity.
        val globals = createSandboxGlobals()
        val chunk = globals.load(
            java.io.ByteArrayInputStream(loaded.bytecode),
            "script",
            "b",
            globals,
        )
        val greet = chunk.call()
        assertTrue(greet.isfunction())
        assertEquals("hi", greet.call().tojstring())
    }
}
