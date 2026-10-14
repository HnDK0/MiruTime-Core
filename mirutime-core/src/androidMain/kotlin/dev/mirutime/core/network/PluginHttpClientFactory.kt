package dev.mirutime.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

// PluginHttpClientFactory: Android picks the OkHttp engine.
actual fun createEngine(): HttpClientEngine = OkHttp.create()
