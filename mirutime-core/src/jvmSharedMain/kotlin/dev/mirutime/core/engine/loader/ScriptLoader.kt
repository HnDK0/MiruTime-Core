package dev.mirutime.core.engine.loader

import dev.mirutime.core.engine.createSandboxGlobals
import dev.mirutime.core.host.FileSystem
import dev.mirutime.core.network.cache.sha256Hex
import kotlinx.coroutines.runBlocking
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaValue
import org.luaj.vm2.compiler.DumpState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader

// Raised when a source cannot be loaded: sha mismatch, missing file, etc.
class ScriptLoadException(message: String) : Exception(message)

// Tag of the LuaJ bytecode format embedded in .lbc file names: an engine
// upgrade changes the dump format and must invalidate every cached file
// (NoveLA precedent, LuaSourceLoader.LUAC_TAG).
internal const val LUAC_TAG = "3.0.1"

/** Compiles Lua text into serialized bytecode (the body of a .lbc cache entry). */
internal fun compileLuaBytecode(globals: Globals, luaCode: String, chunkName: String): ByteArray {
    val proto = globals.compilePrototype(StringReader(luaCode), chunkName)
    return ByteArrayOutputStream().also { DumpState.dump(proto, it, false) }.toByteArray()
}

/** Loads serialized bytecode as a chunk function (mode "b"), env = globals. */
internal fun loadLuaBytecodeChunk(globals: Globals, bytes: ByteArray, chunkName: String): LuaValue =
    globals.load(ByteArrayInputStream(bytes), chunkName, "b", globals)

// Loads Lua plugin scripts from a local path or URL with sha256 verification,
// an atomic on-disk commit (temp write + rename), and a Prototype-dump
// bytecode cache (.lbc) keyed by the source sha256 — a changed sha naturally
// lands on a new cache file, so stale bytecode is never served.
// DI: FileSystem + a suspend fetcher for URL sources (same bridging pattern
// as NetworkBindings: runBlocking on this session-worker thread only).
class ScriptLoader(
    private val fileSystem: FileSystem,
    private val fetcher: suspend (String) -> ByteArray,
    private val scriptDir: String = "lua_scripts",
    private val bytecodeDir: String = "lua_bytecode",
) {
    data class LoadedScript(val code: String, val sha256: String, val bytecode: ByteArray)

    fun load(pathOrUrl: String, expectedSha256: String): LoadedScript {
        val bytes = readSource(pathOrUrl)
        val code = bytes.decodeToString()
        val actual = sha256Hex(bytes)
        // Rollback: a mismatch aborts BEFORE any write — nothing is committed.
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            throw ScriptLoadException(
                "sha256 mismatch for $pathOrUrl: expected $expectedSha256, got $actual",
            )
        }
        atomicWrite(fileSystem, "$scriptDir/${fileNameOf(pathOrUrl)}", bytes)
        return LoadedScript(code, actual, bytecodeFor(actual, code))
    }

    private fun bytecodeFor(sha256: String, code: String): ByteArray {
        val cachePath = "$bytecodeDir/$sha256-$LUAC_TAG.lbc"
        fileSystem.read(cachePath)?.let { cached ->
            val payload = stripHeader(cached, sha256)
            if (payload != null && bytecodeLoads(payload)) return payload
            // Missing/mismatched sha header, or unloadable: drop and recompile.
            fileSystem.delete(cachePath)
        }
        val compiled = compileLuaBytecode(createSandboxGlobals(), code, "script")
        atomicWrite(fileSystem, cachePath, sha256.encodeToByteArray() + compiled)
        return compiled
    }

    // The .lbc body is prefixed with the sha256 of the VERIFIED source it was
    // compiled from, so a swapped-in file (valid bytecode, wrong provenance)
    // is detected at read time and recompiled — loadability alone is not
    // proof of provenance. ceiling: a local attacker who forges the prefix
    // defeats this; the threat model is accidental corruption/desync, not a
    // malicious local writer.
    private fun stripHeader(cached: ByteArray, sha256: String): ByteArray? {
        val header = sha256.encodeToByteArray()
        if (cached.size <= header.size) return null
        for (i in header.indices) if (cached[i] != header[i]) return null
        return cached.copyOfRange(header.size, cached.size)
    }

    private fun bytecodeLoads(bytes: ByteArray): Boolean = try {
        loadLuaBytecodeChunk(createSandboxGlobals(), bytes, "script")
        true
    } catch (e: Exception) {
        false
    }

    private fun readSource(pathOrUrl: String): ByteArray =
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            runBlocking { fetcher(pathOrUrl) }
        } else {
            fileSystem.read(pathOrUrl)
                ?: throw ScriptLoadException("script not found: $pathOrUrl")
        }

    // Atomic commit: temp file + rename, so a crash mid-write never leaves a
    // half script at the final path.
    private fun atomicWrite(fs: FileSystem, path: String, data: ByteArray) {
        val tmp = "$path.tmp"
        fs.write(tmp, data)
        fs.rename(tmp, path)
    }

    private fun fileNameOf(pathOrUrl: String): String =
        pathOrUrl.substringAfterLast('/').substringAfterLast('\\')
}
