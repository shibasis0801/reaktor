package dev.shibasis.reaktor.core.framework

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

// Dispatchers.Default caches a Kotlin object on window. Separately linked JS
// libraries cannot share its coroutine-context identity or generated methods.
actual val reaktorDefaultDispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val schedule: dynamic = js("globalThis.setTimeout")
        schedule({ block.run() }, 0)
    }
}
