package dev.mirutime.core.host

interface Logger {
    fun d(tag: String, msg: String)

    fun w(tag: String, msg: String)

    fun e(tag: String, msg: String, t: Throwable? = null)
}
