package dev.shibasis.reaktor.devtools

import dev.shibasis.reaktor.portgraph.attach.Attachable
import dev.shibasis.reaktor.portgraph.port.PortInterceptor
import dev.shibasis.reaktor.portgraph.port.PortInvocation
import dev.shibasis.reaktor.portgraph.port.addInterceptor
import dev.shibasis.reaktor.portgraph.port.removeInterceptor
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.delay

/**
 * What to do to a call instead of letting it through.
 *
 * Failure and latency are the two things a developer cannot reproduce on demand and most needs to:
 * a retry path that has never run is a retry path that does not work, and a spinner that has never
 * been seen for two seconds has never been designed.
 */
sealed interface Fault {
    /** Throw instead of calling. */
    data class Fail(val message: String) : Fault

    /** Call, but only after waiting. */
    data class Delay(val millis: Long) : Fault

    /** Wait, then throw. The shape of a timeout. */
    data class TimeOut(val millis: Long, val message: String) : Fault
}

/**
 * Applies faults to calls crossing instrumented ports.
 *
 * A K1 interceptor, so it composes with the tap rather than replacing it — an injected failure is
 * still recorded as a port fact, which is the point: the developer sees the failure they asked
 * for arriving through the same pipe as a real one.
 *
 * Suppression is deliberately limited to throwing. Returning a substitute value would require
 * synthesising a value of the call's declared type, which the kernel cannot do and which would
 * silently break any caller that expected something else.
 */
class FaultInjector : PortInterceptor {
    private val faults = atomic(emptyMap<String, Fault>())

    val active: Map<String, Fault> get() = faults.value

    fun set(portKey: String, fault: Fault) = faults.update { it + (portKey to fault) }

    fun clear(portKey: String) = faults.update { it - portKey }

    fun clearAll() = faults.update { emptyMap() }

    override fun intercept(invocation: PortInvocation, proceed: () -> Any?): Any? {
        return when (val fault = faults.value[invocation.port.key.key]) {
            null -> proceed()
            is Fault.Fail -> throw InjectedFault(fault.message)
            // A blocking call cannot be delayed without blocking the caller's thread, which would
            // change the very timing the developer is testing. Non-suspending calls therefore
            // take the failure shapes only, and the refusal says so.
            is Fault.Delay -> proceed()
            is Fault.TimeOut -> throw InjectedFault(fault.message)
        }
    }

    override suspend fun interceptSuspend(invocation: PortInvocation, proceed: suspend () -> Any?): Any? {
        return when (val fault = faults.value[invocation.port.key.key]) {
            null -> proceed()
            is Fault.Fail -> throw InjectedFault(fault.message)
            is Fault.Delay -> {
                delay(fault.millis)
                proceed()
            }

            is Fault.TimeOut -> {
                delay(fault.millis)
                throw InjectedFault(fault.message)
            }
        }
    }

    /** Installs on the given ports and returns the undo. */
    fun install(ports: List<Attachable>): Cancellable {
        ports.forEach { it.addInterceptor(this) }
        return Cancellable {
            ports.forEach { it.removeInterceptor(this) }
            clearAll()
        }
    }

    /** The command surface the workbench drives this through. */
    fun handler(): CommandHandler = commandHandler(
        AgentCapability.FaultInjection,
        "fail",
        "delay",
        "timeout",
        "clear",
        "list",
    ) { command ->
        val portKey = command.arguments["port"].orEmpty()
        val millis = command.arguments["millis"]?.toLongOrNull() ?: 1_000
        val message = command.arguments["message"] ?: "Injected by Reaktor DevTools"
        when (command.action) {
            "list" -> AgentCommandResult(
                command.id,
                true,
                "${faults.value.size} faults",
                payload = faults.value.entries.joinToString(";") { "${it.key}=${it.value}" },
            )

            "clear" -> {
                if (portKey.isBlank()) clearAll() else clear(portKey)
                AgentCommandResult(command.id, true, "Cleared ${portKey.ifBlank { "all faults" }}")
            }

            else -> {
                if (portKey.isBlank()) {
                    AgentCommandResult(command.id, false, "A port key is required")
                } else {
                    val fault = when (command.action) {
                        "fail" -> Fault.Fail(message)
                        "delay" -> Fault.Delay(millis)
                        else -> Fault.TimeOut(millis, message)
                    }
                    set(portKey, fault)
                    AgentCommandResult(command.id, true, "$portKey will $fault")
                }
            }
        }
    }
}

/** Thrown by an injected fault, so a real failure is never mistaken for a simulated one. */
class InjectedFault(message: String) : RuntimeException(message)
