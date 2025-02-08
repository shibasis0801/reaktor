package dev.shibasis.reaktor.core.utils

fun<T> succeed(value: T) = Result.success(value)
fun<T> fail(message: String) = Result.failure<T>(Throwable(message))
fun<T> fail(throwable: Throwable) = Result.failure<T>(throwable)
