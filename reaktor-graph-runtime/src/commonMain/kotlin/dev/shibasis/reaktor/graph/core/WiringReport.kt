package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.portgraph.Unique
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.flattenedValues

enum class WiringOutcome { Connected, DiSatisfiable, Unmatched }

data class WiringEntry(
    val graph: String,
    val owner: String,
    val key: String,
    val type: String,
    val outcome: WiringOutcome,
) {
    val qualifier: String get() = "Port:$key:$type"
}

data class WiringReport(val entries: List<WiringEntry>) {
    val unmatched: List<WiringEntry> get() = entries.filter { it.outcome == WiringOutcome.Unmatched }
    val diSatisfiable: List<WiringEntry> get() = entries.filter { it.outcome == WiringOutcome.DiSatisfiable }
    val isFullyWired: Boolean get() = unmatched.isEmpty() && diSatisfiable.isEmpty()
}

fun Graph.wiringReport(): WiringReport = WiringReport(collectWiring())

private fun Graph.collectWiring(): List<WiringEntry> {
    val own = nodes.flatMap { node ->
        node.consumerPorts.flattenedValues<ConsumerPort<Any>>().map { consumer ->
            val outcome = when {
                consumer.isConnected() -> WiringOutcome.Connected
                resolvesThroughDi(consumer.qualifier) -> WiringOutcome.DiSatisfiable
                else -> WiringOutcome.Unmatched
            }
            WiringEntry(
                graph = label,
                owner = (consumer.owner as? Unique)?.label ?: consumer.owner.toString(),
                key = consumer.key.key,
                type = consumer.type.type,
                outcome = outcome,
            )
        }
    }
    val nested = nodes.filterIsInstance<ContainerNode>()
        .flatMap { container -> container.graphs.flatMap { it.collectWiring() } }
    return own + nested
}

private fun Graph.resolvesThroughDi(qualifier: String): Boolean = try {
    diScope.get(ProviderPort::class, qualifier) != null
} catch (error: Throwable) {
    false
}
