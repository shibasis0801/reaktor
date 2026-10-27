package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import kotlinx.serialization.Serializable
import dev.shibasis.reaktor.graph.NodeIdentity
import dev.shibasis.reaktor.graph.serviceInterceptors
import dev.shibasis.reaktor.portgraph.port.*
import dev.shibasis.reaktor.service.contract.*

@Serializable
data class DeploymentTopology(val schema: Int = 1, val deployments: List<DeploymentDeclaration>)

@Serializable
data class DeploymentDeclaration(
    val worker: String,
    val environment: String,
    val bindings: List<BindingDeclaration> = emptyList(),
    val crons: List<String> = emptyList(),
    val queues: List<String> = emptyList(),
    val routes: List<String> = emptyList(),
)

@Serializable
data class BindingDeclaration(val name: String, val kind: String, val target: String? = null)

class BindingNode(graph: Graph, val binding: BindingDeclaration) : BasicNode(graph), ShapeAttributes {
    override val label = binding.name
    private val type = Type("resource:${binding.name}")
    private val identity = NodeIdentity(this, type)
    val access = registerProvider<ResourceExecutor>(Key("access"), type, object : ResourceExecutor {
        override suspend fun <T> execute(operation: String, effect: Effect, action: suspend () -> T): T =
            activity("resource:${binding.name}", operation, Unit, effect, identity(), graph.serviceInterceptors()) { action() }
    })
    override val kind = when (binding.kind) {
        "hyperdrive", "d1_databases", "kv_namespaces", "r2_buckets" -> NodeKind.Store
        "durable_objects" -> NodeKind.Actor
        else -> NodeKind.External
    }
    override val shapeAttributes = buildMap {
        put("binding", binding.name)
        put("bindingKind", binding.kind)
        put("evidence", "SourceDeclared")
        binding.target?.let { put("target", it) }
    }
}

fun Graph.declareBindings(deployment: DeploymentDeclaration): List<BindingNode> =
    deployment.bindings.map { binding -> Node { BindingNode(it, binding) } }
