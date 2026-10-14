package dev.mirutime.core.network

import io.ktor.client.engine.HttpClientEngine

// Platform engine selection for PluginHttpClient: CIO on JVM, OkHttp on Android.
// Consumers hand the result to PluginHttpClient(engine = createEngine()).
expect fun createEngine(): HttpClientEngine
