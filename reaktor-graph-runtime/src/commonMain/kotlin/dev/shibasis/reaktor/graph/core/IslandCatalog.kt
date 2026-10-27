package dev.shibasis.reaktor.graph.core

import kotlinx.serialization.Serializable

@Serializable
data class IslandCatalog(
    val schema: Int = 1,
    val environment: String,
    val sourceRevision: String,
    val islands: List<IslandShape>,
    val deployments: List<DeploymentDeclaration> = emptyList(),
)

fun DeploymentDeclaration.sourceShape(): IslandShape {
    val scope = "worker/$worker"
    val source = mapOf("evidence" to "SourceDeclared", "environment" to environment)
    val nodes = buildList {
        add(NodeShape("$scope/http", scope, "HTTP ingress", "DeclaredIngress", NodeKind.Controller,
            attributes = source + ("ingress" to "http")))
        bindings.forEach { binding ->
            add(NodeShape("$scope/binding/${binding.name}", scope, binding.name, "DeclaredBinding", when (binding.kind) {
                "hyperdrive", "d1_databases", "r2_buckets", "kv_namespaces" -> NodeKind.Store
                "durable_objects" -> NodeKind.Actor
                else -> NodeKind.External
            }, attributes = source + buildMap {
                put("binding", binding.name); put("bindingKind", binding.kind)
                binding.target?.let { put("target", it) }
            }))
        }
        crons.forEach { cron -> add(NodeShape("$scope/cron/$cron", scope, cron, "DeclaredSchedule", NodeKind.Schedule,
            attributes = source + ("ingress" to "cron:$cron"))) }
        queues.forEach { queue -> add(NodeShape("$scope/queue/$queue", scope, queue, "DeclaredQueueConsumer", NodeKind.Controller,
            attributes = source + ("ingress" to "queue:$queue"))) }
    }
    return IslandShape(worker, "source-declaration", GraphShape(listOf(ScopeShape(scope, worker)), nodes, emptyList(), emptyList()).withDefinitionIdentity())
}
