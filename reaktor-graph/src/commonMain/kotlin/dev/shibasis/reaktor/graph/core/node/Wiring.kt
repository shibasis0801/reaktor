package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.PortEvent
import dev.shibasis.reaktor.portgraph.port.PortEventListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
 *
 * Two threads meet here on every launch — the node's, waiting, and the one wiring the graph — and
 * both can notice the same connection. The wake-up is therefore something that may be asked for
 * twice and happens once: a `CompletableDeferred`, not a continuation. The first version of this
 * resumed a continuation after checking it was still active, which is two steps, and when the two
 * threads interleaved between them the second resume threw `Already resumed` inside the node's own
 * coroutine. That is the failure this function exists to prevent, caused by it: a node that never
 * starts its work, under a supervisor that says nothing.
 */
suspend fun ConsumerPort<*>.awaitConnected() {
    if (isConnected()) return

    val connected = CompletableDeferred<Unit>()
    val listener: PortEventListener = { event ->
        if (event is PortEvent.Connected && event.port === this && isConnected()) connected.complete(Unit)
    }

    owner.addPortEventListener(listener)
    try {
        // Looked at again now that the listener is in place. A connection that landed before the
        // listener was registered made no sound it could hear.
        if (isConnected()) connected.complete(Unit)
        connected.await()
    } finally {
        // Also on cancellation: a node torn down while waiting must not leave itself listening.
        owner.removePortEventListener(listener)
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
