package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

private val requestContexts: dynamic = js("new WeakMap()")

suspend fun <T> withRequestContext(request: Any, execute: suspend () -> T): T {
    val previous: dynamic = requestContexts.get(request)
    requestContexts.set(request, currentCoroutineContext().minusKey(Job))
    return try { execute() } finally {
        if (previous == null) requestContexts.delete(request) else requestContexts.set(request, previous)
    }
}

fun requestContext(request: Any): CoroutineContext =
    requestContexts.get(request).unsafeCast<CoroutineContext?>() ?: EmptyCoroutineContext
