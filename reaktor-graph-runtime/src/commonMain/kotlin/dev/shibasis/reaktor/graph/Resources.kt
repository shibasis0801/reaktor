package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.*
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.*
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.contract.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

open class ResourceNode(
    graph: Graph,
    val resource: String,
    override val kind: NodeKind,
    private val attributes: Map<String, String> = emptyMap(),
) : BasicNode(graph), ShapeAttributes {
    override val label = resource
    private val type = Type("resource:$resource")
    private val identity = NodeIdentity(this, type)
    val access = registerProvider<ResourceExecutor>(Key("access"), type, object : ResourceExecutor {
        override suspend fun <T> execute(operation: String, effect: Effect, action: suspend () -> T): T =
            activity("resource:$resource", operation, Unit, effect, identity(), graph.serviceInterceptors()) { action() }
    })
    override val shapeAttributes get() = attributes + ("resource" to resource)
}

class ResourcePorts(graph: Graph, bindings: List<ResourceNode>) : BasicNode(graph), ResourceAccess {
    override val label = "Resources"
    private val ports = bindings.associate { binding ->
        val port = registerConsumer<ResourceExecutor>(Key(binding.resource), binding.access.type)
        connect(port, binding.access).getOrThrow()
        binding.resource to port
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

fun Graph.resourcePorts(bindings: List<ResourceNode>): ResourcePorts = Node { ResourcePorts(it, bindings) }

class Uses internal constructor(private val ports: Map<String, ConsumerPort<ResourceExecutor>>) {
    private val wires = ports.mapValues { (_, port) -> WireIdentity(port) }

    suspend operator fun <T> invoke(block: suspend () -> T): T {
        val outer = currentCoroutineContext()[ResourceCalls]?.access
        return withContext(ResourceCalls(object : ResourceAccess {
            override suspend fun <R> execute(binding: String, operation: String, effect: Effect, action: suspend () -> R): R {
                val port = ports[binding] ?: return if (outer != null) outer.execute(binding, operation, effect, action) else action()
                return port.suspended {
                    withContext(CallAttributes(mapOf(ServiceCall.EdgeAttribute to wires.getValue(binding)()))) { execute(operation, effect, action) }
                }
            }
        })) { block() }
    }
}

fun PortCapability.uses(vararg resources: String): Uses =
    Uses(resources.associateWith { registerConsumer<ResourceExecutor>(Key(it), Type("resource:$it")) })
