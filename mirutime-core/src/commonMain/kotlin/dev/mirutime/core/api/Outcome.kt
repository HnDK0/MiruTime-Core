package dev.mirutime.core.api

sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    data class Err(val error: MiruError) : Outcome<Nothing>()
}

inline fun <T> Outcome<T>.getOrNull(): T? = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> null
}

inline fun <T> Outcome<T>.getOrElse(onError: (MiruError) -> T): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> onError(error)
}
