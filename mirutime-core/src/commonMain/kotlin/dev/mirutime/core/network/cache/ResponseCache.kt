package dev.mirutime.core.network.cache

data class CacheKey(
    val method: String,
    val url: String,
    val charset: String?,
    val sourceId: String,
    val headersHash: String,
) {
    companion object {
        // Canonical header string: lowercased names, sorted, "name:value" lines.
        fun from(
            method: String,
            url: String,
            charset: String?,
            sourceId: String,
            headers: Map<String, String>,
        ): CacheKey {
            val canonical = headers.entries
                .sortedBy { it.key.lowercase() }
                .joinToString(separator = "\n") { "${it.key.lowercase()}:${it.value}" }
            return CacheKey(method, url, charset, sourceId, sha256Hex(canonical))
        }
    }
}

data class CachedResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val storedAt: Long,
)

// ponytail: not thread-safe — instances are confined to a single coroutine;
// consumer guards concurrency (no atomicfu/synchronized available in commonMain).
class ResponseCache(
    private val maxEntries: Int = 200,
    private val maxBytes: Long = 50 * 1024 * 1024,
    private val ttlMs: Long = 15 * 60 * 1000,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    // LinkedHashMap keeps insertion order; re-insert on access = LRU touch.
    private val entries = LinkedHashMap<CacheKey, CachedResponse>()
    private var totalBytes: Long = 0

    fun get(key: CacheKey): CachedResponse? {
        val entry = entries[key] ?: return null
        if (clock() - entry.storedAt >= ttlMs) {
            removeEntry(key, entry)
            return null
        }
        // LRU touch: move to the most-recent position.
        entries.remove(key)
        entries[key] = entry
        return entry
    }

    fun put(key: CacheKey, response: CachedResponse) {
        if (response.body.size.toLong() > maxBytes) return
        entries.remove(key)?.let { totalBytes -= it.body.size.toLong() }
        entries[key] = response
        totalBytes += response.body.size.toLong()
        evictIfNeeded()
    }

    fun invalidateBySource(sourceId: String) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val e = iterator.next()
            if (e.key.sourceId == sourceId) {
                totalBytes -= e.value.body.size.toLong()
                iterator.remove()
            }
        }
    }

    fun clear() {
        entries.clear()
        totalBytes = 0
    }

    private fun evictIfNeeded() {
        while (entries.size > maxEntries || totalBytes > maxBytes) {
            val eldest = entries.entries.firstOrNull() ?: break
            removeEntry(eldest.key, eldest.value)
        }
    }

    private fun removeEntry(key: CacheKey, entry: CachedResponse) {
        entries.remove(key)
        totalBytes -= entry.body.size.toLong()
    }
}
