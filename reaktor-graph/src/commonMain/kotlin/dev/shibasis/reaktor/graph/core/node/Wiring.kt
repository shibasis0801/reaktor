package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.PortEvent
import dev.shibasis.reaktor.portgraph.port.PortEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Wait until this port has a provider on the other end.
 *
 * A node is constructed before the graph is wired — `attach`, then `autoWire`, then the ports are
 * connected — so anything a node starts in its own `init` block races the wiring. On a background
 * dispatcher the launch usually loses that race by a hair and everything works; occasionally it
 * wins, `invoke()` throws "Can't invoke functions through unconnected ports", and because nodes run
 * under a `SupervisorJob` with no exception handler the coroutine dies without a sound. The node
 * then sits there, attached and healthy-looking, collecting nothing.
 *
 * Returns immediately when the port is already connected, so the common case costs nothing.
 */
suspend fun ConsumerPort<*>.awaitConnected() {
    if (isConnected()) return

    suspendCancellableCoroutine { continuation ->
        // Re-checked inside the listener registration window: `connect` may have run between the
        // check above and this line, and a listener added afterwards would never hear about it.
        if (isConnected()) {
            continuation.resume(Unit)
            return@suspendCancellableCoroutine
        }

        lateinit var listener: PortEventListener
        listener = { event ->
            if (event is PortEvent.Connected && event.port === this && isConnected()) {
                owner.removePortEventListener(listener)
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        owner.addPortEventListener(listener)
        continuation.invokeOnCancellation { owner.removePortEventListener(listener) }

        // One last look, in case the connection landed while the listener was being registered.
        if (isConnected()) {
            owner.removePortEventListener(listener)
            if (continuation.isActive) continuation.resume(Unit)
        }
    }
}

/**
 * Start work that needs ports, once those ports have providers.
 *
 * The safe replacement for `init { launch { somePort().something() } }`. Every consumer named here
 * is awaited before [block] runs, so a node can declare what it needs and then be written as though
 * the graph were already wired — which, by the time the body runs, it is.
 *
 * ```kotlin
 * init {
 *     launchWhenWired(room, identity) { consumeRoom() }
 * }
 * ```
 */
fun Node.launchWhenWired(
    vararg ports: ConsumerPort<*>,
    block: suspend CoroutineScope.() -> Unit,
): Job = coroutineScope.launch {
    ports.forEach { it.awaitConnected() }
    block()
}
