package dev.mirutime.core.host

interface KeyValueStore {
    fun get(namespace: String, key: String): String?

    fun set(namespace: String, key: String, value: String)

    fun remove(namespace: String, key: String)
}
