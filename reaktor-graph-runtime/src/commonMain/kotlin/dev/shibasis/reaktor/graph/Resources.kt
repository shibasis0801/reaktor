package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.*
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.*
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.contract.*
import kotlinx.coroutines.withContext

class ResourcePorts(graph: Graph, bindings: List<BindingNode>) : BasicNode(graph), ResourceAccess {
    override val label = "Resources"
    private val ports = bindings.associate { binding ->
        val port = registerConsumer<ResourceExecutor>(Key(binding.binding.name), binding.access.type)
        connect(port, binding.access).getOrThrow()
        binding.binding.name to port
    }

    override suspend fun <T> execute(binding: String, operation: String, effect: Effect, action: suspend () -> T): T {
        val port = requireNotNull(ports[binding]) { "Resource '$binding' is not declared in ${graph.label}" }
        return port.suspended {
            withContext(CallAttributes(mapOf(ServiceCall.EdgeAttribute to WireIdentity(port)()))) {
                execute(operation, effect, action)
            }
        }
    }
}

fun Graph.resourcePorts(bindings: List<BindingNode>): ResourcePorts = Node { ResourcePorts(it, bindings) }
