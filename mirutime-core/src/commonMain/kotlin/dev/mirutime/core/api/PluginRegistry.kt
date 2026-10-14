package dev.mirutime.core.api

import dev.mirutime.core.network.HostOptionsRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Everything needed to install one plugin: its metadata (CF/UA presets
// included) and the Lua source text pinned by metadata.sha256.
data class PluginDescriptor(
    val metadata: PluginMetadata,
    val script: String,
)

// Seam for Task 15: builds the concrete PluginSource. The JVM implementation
// owns createLuaSession + binding install order (bindings/require_lib BEFORE
// the chunk executes — see the Task 13 seam note); tests inject a fake.
fun interface PluginSourceFactory {
    fun create(descriptor: PluginDescriptor): PluginSource
}

sealed class PluginState {
    object Loaded : PluginState()
    data class Failed(
        val pluginId: String,
        val reason: String,
        // Typed cause when one exists (e.g. UnsupportedApi) — reason stays
        // the human-readable summary.
        val error: PluginError? = null,
    ) : PluginState()
}

class PluginHandle internal constructor(
    val descriptor: PluginDescriptor,
    // Null exactly when state is Failed — creation did not produce a source.
    val source: PluginSource?,
    val state: StateFlow<PluginState>,
    private val hostOptions: HostOptionsRegistry,
    private val host: String,
) {
    var isDisposed: Boolean = false
        private set

    // Idempotent: removes THIS plugin's CF/UA host registrations — entries
    // owned by other plugins on the same host survive.
    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        hostOptions.clear(host, descriptor.metadata.id)
    }
}

// Lifecycle owner for installed plugins: builds the PluginSource through the
// factory seam, tracks Loaded/Failed state, and registers the
// plugin's CF/UA presets in the per-host mapping (NoveLA precedent:
// LuaCfOptionsRegistry + UA preset registration, cleared on unload).
class PluginRegistry(
    private val sourceFactory: PluginSourceFactory,
    private val hostOptions: HostOptionsRegistry,
) {
    fun install(descriptor: PluginDescriptor): PluginHandle {
        val host = hostOf(descriptor.metadata.baseUrl)
        // install is synchronous with no suspension point, so an intermediate
        // Loading state would never be observable — the flow starts Loaded and
        // only a failure moves it to Failed.
        val state = MutableStateFlow<PluginState>(PluginState.Loaded)
        // Api-version gate (spec): an incompatible plugin fails VISIBLY with
        // a typed UnsupportedApi — it never loads half-working. The factory
        // is not even asked to create a source.
        if (descriptor.metadata.api != SUPPORTED_PLUGIN_API) {
            state.value = PluginState.Failed(
                descriptor.metadata.id,
                "unsupported plugin api ${descriptor.metadata.api}: " +
                    "library supports $SUPPORTED_PLUGIN_API",
                error = PluginError.UnsupportedApi(
                    required = descriptor.metadata.api,
                    actual = SUPPORTED_PLUGIN_API,
                ),
            )
            return PluginHandle(descriptor, null, state, hostOptions, host)
        }
        val source = try {
            sourceFactory.create(descriptor)
        } catch (e: Exception) {
            // Failed install registers nothing — no source, no host entries.
            state.value = PluginState.Failed(
                descriptor.metadata.id,
                e.message ?: e::class.simpleName ?: "install failed",
            )
            return PluginHandle(descriptor, null, state, hostOptions, host)
        }
        // Per-host registries: register after the source exists, keyed by
        // this plugin as owner; handle.dispose() removes only its own entry.
        // A failed install registers nothing, so its dispose is a no-op.
        val owner = descriptor.metadata.id
        descriptor.metadata.cfOptions?.let { hostOptions.setCfOptions(host, owner, it) }
        descriptor.metadata.userAgentPreset?.let { hostOptions.setUserAgent(host, owner, it) }
        return PluginHandle(descriptor, source, state, hostOptions, host)
    }

    // "https://www.Site.Example:8080/x" -> "site.example" via the shared
    // network-package normalization (one key for registry, gate and client).
    private fun hostOf(baseUrl: String): String =
        dev.mirutime.core.network.normalizeHost(
            baseUrl.substringAfter("://").substringBefore("/"),
        )
}
