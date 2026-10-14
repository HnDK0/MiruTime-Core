package dev.mirutime.core.host

class FakeKeyValueStore : KeyValueStore {
    private val data = mutableMapOf<String, MutableMap<String, String>>()

    override fun get(namespace: String, key: String): String? = data[namespace]?.get(key)

    override fun set(namespace: String, key: String, value: String) {
        data.getOrPut(namespace) { mutableMapOf() }[key] = value
    }

    override fun remove(namespace: String, key: String) {
        data[namespace]?.remove(key)
    }
}

class FakeCookieStore : CookieStore {
    val cookies = mutableListOf<Cookie>()

    override suspend fun get(url: String): List<Cookie> {
        val host = hostOf(url)
        return cookies.filter { it.domain == host }
    }

    override suspend fun setFromResponse(url: String, setCookieHeaders: List<String>) {
        val host = hostOf(url)
        setCookieHeaders.forEach { header ->
            val parts = header.split(';').first().split('=', limit = 2)
            if (parts.size == 2) {
                cookies += Cookie(parts[0].trim(), parts[1].trim(), domain = host)
            }
        }
    }

    override suspend fun clear(host: String) {
        cookies.removeAll { it.domain == host }
    }

    override suspend fun clearCookies(host: String, names: List<String>) {
        cookies.removeAll { it.domain == host && it.name in names }
    }

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore("/")
}

class FakeCfChallengeSolver : CfChallengeSolver {
    var nextSolution: CfSolution? = null
    val requests = mutableListOf<CfChallenge>()

    override suspend fun solve(request: CfChallenge): CfSolution? {
        requests += request
        return nextSolution
    }
}

class RecordingLogger : Logger {
    val lines = mutableListOf<String>()

    override fun d(tag: String, msg: String) {
        lines += "D/$tag: $msg"
    }

    override fun w(tag: String, msg: String) {
        lines += "W/$tag: $msg"
    }

    override fun e(tag: String, msg: String, t: Throwable?) {
        lines += "E/$tag: $msg${t?.let { " (${it.message})" } ?: ""}"
    }
}

class FakeSystemInfo(
    override val defaultUserAgent: String = "MiruTimeTest/1.0",
    override val acceptLanguage: String = "en-US",
) : SystemInfo

class FakeFileSystem : FileSystem {
    val files = mutableMapOf<String, ByteArray>()

    override fun read(path: String): ByteArray? = files[path]

    override fun write(path: String, data: ByteArray) {
        files[path] = data
    }

    override fun exists(path: String): Boolean = path in files

    override fun delete(path: String) {
        files.remove(path)
    }

    override fun rename(from: String, to: String) {
        val data = files.remove(from) ?: error("rename: source not found: $from")
        files[to] = data
    }

    override fun list(dir: String): List<String> =
        files.keys.filter { it.startsWith("$dir/") }.sorted()
}
