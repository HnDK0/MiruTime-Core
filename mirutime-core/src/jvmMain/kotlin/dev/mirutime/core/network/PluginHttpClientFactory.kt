package dev.mirutime.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

// PluginHttpClientFactory: JVM picks the CIO engine.
actual fun createEngine(): HttpClientEngine = CIO.create()
