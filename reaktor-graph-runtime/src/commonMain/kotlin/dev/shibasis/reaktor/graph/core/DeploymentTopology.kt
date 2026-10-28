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
data class BindingDeclaration(val name: String, val kind: String, val target: String? = null, val ownerScript: String? = null, val environments: Set<String> = emptySet())

class BindingNode(graph: Graph, val binding: BindingDeclaration) : dev.shibasis.reaktor.graph.ResourceNode(
    graph, binding.name, when (binding.kind) {
        "hyperdrive", "d1_databases", "kv_namespaces", "r2_buckets", "vectorize" -> NodeKind.Store
        "durable_objects" -> NodeKind.Actor
        else -> NodeKind.External
    }, buildMap {
        put("binding", binding.name)
        put("bindingKind", binding.kind)
        if (binding.environments.isNotEmpty()) put("environments", binding.environments.sorted().joinToString(","))
        put("evidence", "SourceDeclared")
        binding.target?.let { put("target", it) }
        binding.ownerScript?.let { put("ownerScript", it) }
    },
)

fun Graph.declareBindings(deployment: DeploymentDeclaration): List<BindingNode> =
    deployment.bindings.map { binding -> Node { BindingNode(it, binding) } }
