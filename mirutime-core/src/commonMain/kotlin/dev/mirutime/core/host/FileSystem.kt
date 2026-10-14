package dev.mirutime.core.host

interface FileSystem {
    fun read(path: String): ByteArray?

    fun write(path: String, data: ByteArray)

    fun exists(path: String): Boolean

    fun delete(path: String)

    // Atomic-ish move used by temp-write-then-rename commits (ScriptLoader).
    fun rename(from: String, to: String)

    fun list(dir: String): List<String>
}
