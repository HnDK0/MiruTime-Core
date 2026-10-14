package dev.mirutime.core.installer

import dev.mirutime.core.api.SUPPORTED_PLUGIN_API
import dev.mirutime.core.engine.createSandboxGlobals
import dev.mirutime.core.host.FileSystem
import dev.mirutime.core.network.cache.sha256Hex
import dev.mirutime.core.network.security.InetAddressHostResolver
import dev.mirutime.core.network.security.SsrfGuard
import dev.mirutime.core.network.security.SsrfVerdict
import kotlinx.coroutines.CancellationException

sealed class InstallResult {
    data class Success(
        val path: String,
        val sha256: String?,
        // Parsed from the script header for plugin sources; null for shared
        // libraries, which carry no plugin metadata.
        val metadata: ScriptMetadata? = null,
    ) : InstallResult()

    data class Failed(val reason: String) : InstallResult()
}

// Sha-verified install pipeline (external-sources index.yaml):
// download -> sha256 verify -> syntax validation -> test load -> atomic
// commit. Every check runs BEFORE any write, so a failed install leaves the
// previously installed file untouched (rollback = never touched it).
// Sources land in [installDir] and carry the metadata parsed from their
// header; the index `libraries:` entries land in [libDir] (the path
// require_lib resolves) through installLibrary/installLibraries.
// DI: FileSystem + a suspend fetcher for URLs (same bridging pattern as
// ScriptLoader — tests inject a fake "http").
// ceiling: full plugin EXECUTION happens at registry install, where
// libs/bindings are present; the header run in ScriptMetadataParser is
// sandboxed and tolerates a later throw, so a broken chunk is caught here
// without the engine bindings.
class PluginInstaller(
    private val fileSystem: FileSystem,
    private val fetcher: suspend (String) -> ByteArray,
    private val installDir: String = "lua_plugins",
    // require_lib resolves lua_libs (LuaPluginSourceFactory.libDir default) —
    // installed index libraries must land here to be reachable.
    private val libDir: String = "lua_libs",
    // Guarded by default (same default as LuaPluginSourceFactory); pass null
    // to disable — an index URL is remote-controlled, so it is validated
    // before ANY fetch, exactly like PluginHttpClient validates request URLs.
    private val ssrfGuard: SsrfGuard? = SsrfGuard(InetAddressHostResolver()),
) {
    suspend fun install(entry: IndexEntry): InstallResult {
        // The id is a remote-controlled filesystem path segment: reject
        // anything but a path-safe token before it reaches the disk
        // (LibraryLoader.ID_PATTERN precedent; ids like "../../evil" would
        // otherwise write outside installDir). Real catalog ids include
        // dotted and mixed-case slugs ("mvlempyr.com", "NovelArrow").
        // NoveLA precedent (LuaSourceLoader.kt:1409-1410 isValidExtensionId):
        // any ".." is rejected even though single dots stay legal.
        if (!ID_PATTERN.matches(entry.id) || entry.id == "." || entry.id == ".." || entry.id.contains("..")) {
            return InstallResult.Failed("invalid id '${entry.id}': must match ${ID_PATTERN.pattern}")
        }
        // Api gate mirrors the registry load gate: an entry published against
        // an incompatible API never reaches the network (null = index field
        // absent, unconstrained here — metadata.api still gates at load).
        if (entry.api != null && entry.api != SUPPORTED_PLUGIN_API) {
            return InstallResult.Failed(
                "unsupported api ${entry.api} for '${entry.id}': " +
                    "library supports $SUPPORTED_PLUGIN_API",
            )
        }
        // The index URL is remote-controlled: refuse hostile targets before
        // the fetcher ever sees them (mirrors the api gate above — no network
        // for entries that cannot install).
        ssrfRefusal(entry.url)?.let { return InstallResult.Failed(it) }
        val expected = entry.sha256
        return when (val fetched = if (expected != null) fetchVerified(entry, expected) else fetchUnverified(entry)) {
            is Fetch.Err -> InstallResult.Failed(fetched.reason)
            is Fetch.Ok -> {
                val code = fetched.bytes.decodeToString()
                syntaxError(code, entry.id)?.let { return InstallResult.Failed(it) }
                // Attach the script's own metadata so a real external-sources
                // entry can be installed and loaded without host hardcoding.
                val metadata = ScriptMetadataParser.parse(code)
                // Atomic commit: temp file + rename (ScriptLoader pattern) — a
                // crash mid-write never leaves a half script at the final path.
                val path = "$installDir/${entry.id}.lua"
                commit(path, fetched.bytes)
                InstallResult.Success(path, fetched.sha256, metadata)
            }
        }
    }

    // Installs one shared library from the index `libraries:` section into
    // [libDir] so a plugin's require_lib(id) resolves it. Library ids obey the
    // require_lib id rule ([a-z0-9_]+) — stricter than plugin ids.
    suspend fun installLibrary(entry: IndexEntry): InstallResult {
        if (!LIB_ID_PATTERN.matches(entry.id) || entry.id == "." || entry.id == "..") {
            return InstallResult.Failed(
                "invalid library id '${entry.id}': must match ${LIB_ID_PATTERN.pattern}",
            )
        }
        val expected = entry.sha256
            ?: return InstallResult.Failed("library '${entry.id}' has no sha256")
        ssrfRefusal(entry.url)?.let { return InstallResult.Failed(it) }
        return when (val fetched = fetchVerified(entry, expected)) {
            is Fetch.Err -> InstallResult.Failed(fetched.reason)
            is Fetch.Ok -> {
                syntaxError(fetched.bytes.decodeToString(), entry.id)?.let {
                    return InstallResult.Failed(it)
                }
                val path = "$libDir/${entry.id}.lua"
                commit(path, fetched.bytes)
                InstallResult.Success(path, fetched.sha256)
            }
        }
    }

    // Installs every library of a parsed index (root or language index).
    suspend fun installLibraries(index: PluginIndex): List<InstallResult> =
        index.libraries.map { installLibrary(it) }

    // sha256 of the installed script file, null when not installed.
    fun installedSha256(id: String): String? =
        fileSystem.read("$installDir/$id.lua")?.let { sha256Hex(it) }

    // Update decision by sha256 only (external-sources rule): the catalog
    // hash differs from the local file's hash. Versions ("1.7.1") play no
    // part — string versions cannot be reliably ordered.
    fun isUpdateAvailable(entry: IndexEntry): Boolean {
        val catalog = entry.sha256 ?: return false
        val local = installedSha256(entry.id) ?: return true
        return !local.equals(catalog, ignoreCase = true)
    }

    // SSRF verdict for an index URL, null when the fetch may proceed. A refusal
    // becomes an ordinary Failed (same contract as the id/api gates): a failed
    // install never touches the installed file.
    private fun ssrfRefusal(url: String): String? {
        val guard = ssrfGuard ?: return null
        return when (val verdict = guard.isSafe(url)) {
            is SsrfVerdict.Blocked -> "SSRF blocked for '$url': ${verdict.reason}"
            SsrfVerdict.DnsFailure -> "DNS resolution failed for '$url'"
            SsrfVerdict.Ok -> null
        }
    }

    // Download + sha256 check shared by sources and libraries; cancellation is
    // never converted into an ordinary Failed result.
    private suspend fun fetchVerified(entry: IndexEntry, expected: String): Fetch {
        val bytes = try {
            fetcher(entry.url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Fetch.Err("download failed for '${entry.id}': ${e.message}")
        }
        val actual = sha256Hex(bytes)
        if (!actual.equals(expected, ignoreCase = true)) {
            return Fetch.Err("sha256 mismatch for '${entry.id}': expected $expected, got $actual")
        }
        return Fetch.Ok(bytes, actual)
    }

    // Download without sha256 verification: syntax check + atomic commit only.
    // Used when the index entry lacks a sha256 (real indexes often omit it).
    private suspend fun fetchUnverified(entry: IndexEntry): Fetch {
        val bytes = try {
            fetcher(entry.url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Fetch.Err("download failed for '${entry.id}': ${e.message}")
        }
        return Fetch.Ok(bytes, null)
    }

    // Compile + load (never call) in a fresh sandbox: broken syntax is caught
    // without executing plugin logic. null = ok, otherwise the failure reason.
    private fun syntaxError(code: String, id: String): String? = try {
        createSandboxGlobals().load(code, "@$id")
        null
    } catch (e: Exception) {
        "script '$id' failed to load: ${e.message}"
    }

    // Atomic commit: temp file + rename (ScriptLoader pattern).
    private fun commit(path: String, bytes: ByteArray) {
        fileSystem.write("$path.tmp", bytes)
        fileSystem.rename("$path.tmp", path)
    }

    private sealed interface Fetch {
        data class Ok(val bytes: ByteArray, val sha256: String?) : Fetch
        data class Err(val reason: String) : Fetch
    }

    private companion object {
        // Path-safe token: letters (any case), digits, "_", "-" and "." —
        // no separators ("/", "\"), so traversal is impossible; "."/".." are
        // rejected explicitly by the caller check.
        val ID_PATTERN = Regex("[A-Za-z0-9_.-]+")

        // require_lib id rule (LibraryLoader.ID_PATTERN): a library id must be
        // a lowercase path-safe token, stricter than plugin ids.
        val LIB_ID_PATTERN = Regex("[a-z0-9_]+")
    }
}
