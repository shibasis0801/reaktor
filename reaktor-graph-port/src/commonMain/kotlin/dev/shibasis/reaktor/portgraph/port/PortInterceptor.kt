package dev.shibasis.reaktor.portgraph.port

import dev.shibasis.reaktor.portgraph.attach.Attachable
import dev.shibasis.reaktor.portgraph.attach.AttachmentKey
import dev.shibasis.reaktor.portgraph.attach.attachment
import dev.shibasis.reaktor.portgraph.attach.update
import dev.shibasis.reaktor.portgraph.edge.Edge

/**
 * K1 — what an interceptor is handed when a call crosses a port.
 *
 * The port and, for a consumer call, the edge it travelled over. Duration, outcome and identity
 * beyond this are the interceptor's own job; the kernel measures nothing.
 */
class PortInvocation(
    val port: Port<*>,
    val edge: Edge<*>? = null,
) {
    override fun toString() = "[PortInvocation] ${port.qualifier}${edge?.let { " via ${it.id}" } ?: ""}"
}

/**
 * Wraps a call crossing a port.
 *
 * Both methods default to passing through, so an implementation overrides only the shape it
 * cares about. Call `proceed` exactly once to run the call, or skip it to suppress the call —
 * a suppressing interceptor must return a value assignable to the call's declared return type,
 * because the kernel cannot synthesise one.
 */
interface PortInterceptor {
    fun intercept(invocation: PortInvocation, proceed: () -> Any?): Any? = proceed()

    suspend fun interceptSuspend(invocation: PortInvocation, proceed: suspend () -> Any?): Any? = proceed()
}

/** Where the chain lives. An attachment, so the kernel gains no field per subsystem. */
val PortInterceptors = AttachmentKey<List<PortInterceptor>>("reaktor.port.interceptors")

fun Attachable.addInterceptor(interceptor: PortInterceptor) {
    update(PortInterceptors) { current -> (current ?: emptyList()) + interceptor }
}

fun Attachable.removeInterceptor(interceptor: PortInterceptor) {
    update(PortInterceptors) { current ->
        val next = current?.filterNot { it === interceptor }
        if (next.isNullOrEmpty()) null else next
    }
}

/** Composes the chain so index 0 is outermost, then runs the call. */
internal fun runInterceptors(
    chain: List<PortInterceptor>,
    invocation: PortInvocation,
    terminal: () -> Any?,
): Any? {
    var next: () -> Any? = terminal
    for (index in chain.indices.reversed()) {
        val interceptor = chain[index]
        val downstream = next
        next = { interceptor.intercept(invocation, downstream) }
    }
    return next()
}

internal suspend fun runInterceptorsSuspend(
    chain: List<PortInterceptor>,
    invocation: PortInvocation,
    terminal: suspend () -> Any?,
): Any? {
    var next: suspend () -> Any? = terminal
    for (index in chain.indices.reversed()) {
        val interceptor = chain[index]
        val downstream = next
        next = { interceptor.interceptSuspend(invocation, downstream) }
    }
    return next()
}

/** Null when nothing is attached, so the uninstrumented path is one volatile read. */
internal fun Port<*>.interceptorChain(): List<PortInterceptor>? {
    if (!hasAttachments) return null
    return attachment(PortInterceptors)?.takeIf { it.isNotEmpty() }
}
