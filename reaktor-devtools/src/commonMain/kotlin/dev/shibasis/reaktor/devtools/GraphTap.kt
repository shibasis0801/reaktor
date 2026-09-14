package dev.shibasis.reaktor.devtools

import dev.shibasis.reaktor.portgraph.Unique
import dev.shibasis.reaktor.portgraph.attach.Attachable
import dev.shibasis.reaktor.portgraph.port.PortEvent
import dev.shibasis.reaktor.portgraph.port.PortEventListener
import dev.shibasis.reaktor.portgraph.port.PortInterceptor
import dev.shibasis.reaktor.portgraph.port.PortInvocation
import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.portgraph.port.addInterceptor
import dev.shibasis.reaktor.portgraph.port.removeInterceptor

/**
 * Records calls crossing a port.
 *
 * This is the whole reason the agent sees meaning rather than bytes: an out-of-process tool can
 * observe a socket, but only something inside the process knows which port issued the call, what
 * contract it declared and which causal chain it belongs to.
 *
 * Installed as a K1 [PortInterceptor] on the ports worth watching rather than on all of them —
 * an unattached port stays at one volatile read, which is the cost the kernel promises.
 */
class GraphTap(private val stream: FactStream) : PortInterceptor {

    override fun intercept(invocation: PortInvocation, proceed: () -> Any?): Any? {
        if (!stream.enabled) return proceed()
        val start = DevToolsClock.nanos()
        return try {
            proceed().also { record(invocation, start, null) }
        } catch (failure: Throwable) {
            record(invocation, start, failure)
            throw failure
        }
    }

    override suspend fun interceptSuspend(invocation: PortInvocation, proceed: suspend () -> Any?): Any? {
        if (!stream.enabled) return proceed()
        val start = DevToolsClock.nanos()
        return try {
            proceed().also { record(invocation, start, null) }
        } catch (failure: Throwable) {
            record(invocation, start, failure)
            throw failure
        }
    }

    private fun record(invocation: PortInvocation, startNanos: Long, failure: Throwable?) {
        val port = invocation.port
        stream.emit { sequence, nanos ->
            AgentFact.Port(
                sequence = sequence,
                monotonicNanos = nanos,
                kind = if (failure == null) PortEventKind.Invoked else PortEventKind.Failed,
                portKey = port.key.key,
                portType = port.type.type,
                nodeId = (port.owner as? Unique)?.id?.toString(),
                peerPortKey = invocation.edge?.id?.toString(),
                durationNanos = nanos - startNanos,
                failure = failure?.let { it::class.simpleName + (it.message?.let { m -> ": " + m } ?: "") },
            )
        }
    }
}

/**
 * Watches ports appearing and being wired together.
 *
 * Structure and traffic are different questions, so lifecycle is a separate listener from the
 * interceptor: a graph can change shape without a single call crossing it. A function rather than
 * a class because `PortEventListener` is a function type, and JavaScript forbids implementing one.
 */
fun graphStructureTap(stream: FactStream): PortEventListener = { event ->
    stream.emit { sequence, nanos ->
        AgentFact.Port(
            sequence = sequence,
            monotonicNanos = nanos,
            kind = when (event) {
                is PortEvent.Created -> PortEventKind.Created
                is PortEvent.Connected -> PortEventKind.Connected
                is PortEvent.Disconnected -> PortEventKind.Disconnected
            },
            portKey = event.port.key.key,
            portType = event.port.type.type,
            peerPortKey = when (event) {
                is PortEvent.Connected -> event.other.key.key
                is PortEvent.Disconnected -> event.other.key.key
                else -> null
            },
        )
    }
}

/** Installs both taps and hands back the undo, so detaching leaves the graph as it was found. */
fun installGraphTaps(
    owner: PortCapability,
    ports: List<Attachable>,
    stream: FactStream,
): () -> Unit {
    val interceptor = GraphTap(stream)
    val listener = graphStructureTap(stream)
    ports.forEach { it.addInterceptor(interceptor) }
    owner.addPortEventListener(listener)
    return {
        ports.forEach { it.removeInterceptor(interceptor) }
        owner.removePortEventListener(listener)
    }
}
