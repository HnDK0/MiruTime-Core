package dev.mirutime.core.network.cache

// Platform wall clock; injected clock in ResponseCache makes TTL deterministic in tests.
// internal: plumbing for ResponseCache/CfGate, not a consumer-facing API.
internal expect fun currentTimeMillis(): Long
