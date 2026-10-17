package dev.shibasis.reaktor.work

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlin.js.Promise

/** Foreground opportunities. Closing the page cannot promise another execution opportunity. */
class BrowserWorkScheduler(
    private val scope: WorkScope,
    private val host: WorkHost,
    private val lifecycle: CoroutineScope,
) : WorkScheduler, AutoCloseable {
    private var timer: dynamic = null
    private val online: (dynamic) -> Unit = { wake() }
    private val visible: (dynamic) -> Unit = { if (js("globalThis.document.visibilityState") == "visible") wake() }
    init {
        js("globalThis").addEventListener("online", online)
        js("globalThis.document").addEventListener("visibilitychange", visible)
        wake()
    }

    override suspend fun arm(scope: WorkScope, nextWakeAtMillis: Long?) {
        require(scope == this.scope)
        if (timer != null) js("globalThis").clearTimeout(timer)
        timer = null
        if (nextWakeAtMillis != null) {
            val delay = (nextWakeAtMillis - (js("Date.now()") as Double).toLong()).coerceIn(0, Int.MAX_VALUE.toLong())
            timer = js("globalThis").setTimeout({ wake() }, delay.toDouble())
        }
    }

    private fun wake() {
        lifecycle.launch {
            val locks: dynamic = js("globalThis.navigator && globalThis.navigator.locks")
            if (locks != null) {
                val promise = locks.request("reaktor-work:${scope.storeName}", { _: dynamic ->
                    lifecycle.promise { drain() }
                }) as Promise<Unit>
                promise.await()
            } else drain()
        }
    }

    private suspend fun drain() {
        val session = host.open(scope)
        try { session.runtime.drain() } finally { session.close() }
    }

    override fun close() {
        if (timer != null) js("globalThis").clearTimeout(timer)
        js("globalThis").removeEventListener("online", online)
        js("globalThis.document").removeEventListener("visibilitychange", visible)
    }
}

/**
 * Install only in a separately built, storage-qualified service-worker host. The browser event
 * retains the promise; a detached launch would not extend event lifetime. Long attempts may still die.
 */
fun bindServiceWorkerWorkEvents(worker: dynamic, scope: WorkScope, host: WorkHost) {
    val drain: () -> Promise<Unit> = {
        CoroutineScope(Dispatchers.Default).promise {
            val session = host.open(scope)
            try { session.runtime.drain() } finally { session.close() }
        }
    }
    worker.addEventListener("sync", { event: dynamic -> if (event.tag == "reaktor-work") event.waitUntil(drain()) })
    worker.addEventListener("periodicsync", { event: dynamic -> if (event.tag == "reaktor-periodic") event.waitUntil(drain()) })
    worker.addEventListener("push", { event: dynamic -> event.waitUntil(drain()) })
    worker.addEventListener("message", { event: dynamic -> if (event.data == "reaktor-work") event.waitUntil(drain()) })
}
