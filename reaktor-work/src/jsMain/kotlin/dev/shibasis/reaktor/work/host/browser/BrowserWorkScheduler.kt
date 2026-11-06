package dev.shibasis.reaktor.work.host.browser

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.work.execution.WorkScheduler
import dev.shibasis.reaktor.work.host.WorkHost
import dev.shibasis.reaktor.work.host.drain
import dev.shibasis.reaktor.work.run.WorkScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlin.js.Promise

class BrowserWorkScheduler(
    private val scope: WorkScope,
    private val host: WorkHost,
    private val lifecycle: CoroutineScope,
) : WorkScheduler, AutoCloseable {
    private val global: dynamic = js("globalThis")
    private val lock = "reaktor-work:${scope.key}"
    private val peers = BroadcastChannel(lock)
    private var timer: dynamic = null
    private var timerAtMillis: Long? = null
    private val online: (dynamic) -> Unit = { wake() }
    private val visible: (dynamic) -> Unit = { if (global.document.visibilityState == "visible") wake() }
    private val relayed: (dynamic) -> Unit = { event -> if (event.data?.type == ServiceWorkerWake) answer(event.ports[0]) }

    init {
        peers.onmessage = { event -> (event.data as? Double)?.let { schedule(it.toLong(), earlierOnly = true) } }
        global.addEventListener("online", online)
        global.document?.addEventListener("visibilitychange", visible)
        global.navigator.serviceWorker?.addEventListener("message", relayed)
        global.navigator.serviceWorker?.startMessages()
        wake()
    }

    override suspend fun arm(scope: WorkScope, nextWakeAtMillis: Long?) {
        require(scope == this.scope) { "This scheduler serves another scope" }
        if (nextWakeAtMillis == null) cancel()
        else {
            schedule(nextWakeAtMillis, earlierOnly = false)
            peers.postMessage(nextWakeAtMillis.toDouble())
        }
    }

    private fun schedule(atMillis: Long, earlierOnly: Boolean) {
        val current = timerAtMillis
        if (earlierOnly && current != null && current <= atMillis) return
        cancel()
        val delay = (atMillis - (js("Date.now()") as Double).toLong()).coerceIn(0, Int.MAX_VALUE.toLong())
        timerAtMillis = atMillis
        timer = global.setTimeout({
            timer = null
            timerAtMillis = null
            wake()
        }, delay.toDouble())
    }

    private fun cancel() {
        if (timer != null) global.clearTimeout(timer)
        timer = null
        timerAtMillis = null
    }

    private suspend fun drain() {
        global.navigator.locks.request(lock) { _: dynamic -> lifecycle.promise { host.drain(scope) } }
            .unsafeCast<Promise<Any?>>().await()
    }

    private fun wake() {
        lifecycle.launch {
            try {
                drain()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Logger.w("ReaktorWork") { "A browser drain failed; trying again shortly: ${failure.message}" }
                schedule((js("Date.now()") as Double).toLong() + RetryAfterFailure, earlierOnly = true)
            }
        }
    }

    private fun answer(port: dynamic) {
        lifecycle.launch {
            val reply = try {
                drain()
                Drained
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failure.message ?: "The drain failed"
            }
            port?.postMessage(reply)
        }
    }

    override fun close() {
        cancel()
        peers.close()
        global.removeEventListener("online", online)
        global.document?.removeEventListener("visibilitychange", visible)
        global.navigator.serviceWorker?.removeEventListener("message", relayed)
    }
}

private external class BroadcastChannel(name: String) {
    var onmessage: ((dynamic) -> Unit)?
    fun postMessage(message: Any?)
    fun close()
}

private const val ServiceWorkerWake = "reaktor-work"
private const val Drained = "drained"
private const val RetryAfterFailure = 30_000L
