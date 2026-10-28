package dev.shibasis.reaktor.graph.core

import kotlinx.serialization.Serializable

@Serializable
data class CoverageCount(val covered: Int, val total: Int) {
    val complete: Boolean get() = covered == total
}

@Serializable
data class DeploymentCoverage(
    val worker: String,
    val environment: String,
    val shape: CoverageCount,
    val bindings: CoverageCount,
    val ingress: CoverageCount,
    val typedClients: CoverageCount,
    val activities: CoverageCount,
    val observedActivities: CoverageCount,
    val findings: List<String>,
    val definitionDigest: String? = null,
) {
    val complete: Boolean get() = findings.isEmpty() && listOf(shape, bindings, ingress, typedClients, activities).all { it.complete }
}

fun DeploymentDeclaration.coverage(
    shape: IslandShape?,
    requiredActivities: Set<String>? = null,
    observedNodes: Set<String> = emptySet(),
): DeploymentCoverage {
    fun flatten(island: IslandShape): List<NodeShape> = island.graph.nodes + island.objects.flatMap(::flatten)
    val nodes = shape?.let(::flatten).orEmpty()
    fun allWires(island: IslandShape): List<WireShape> = island.graph.wires + island.objects.flatMap(::allWires)
    val wires = shape?.let(::allWires).orEmpty()
    val findings = mutableListOf<String>()
    if (shape == null) findings += "Island shape is missing"
    val declaredBindings = bindings.associateBy { it.name }
    val actualBindings = nodes.filter { node ->
        node.attributes["binding"] != null && node.attributes["environments"]?.split(",")?.let { environment in it } != false
    }.groupBy { it.attributes.getValue("binding") }
    val coveredBindings = declaredBindings.count { (name, declaration) ->
        val matches = actualBindings[name].orEmpty()
        (matches.size == 1 && matches.single().attributes["bindingKind"] == declaration.kind).also {
            if (!it) findings += "Binding $name is missing, duplicated or has the wrong kind"
        }
    }
    (actualBindings.keys - declaredBindings.keys).sorted().forEach { findings += "Graph claims undeclared binding $it" }
    val expectedIngress = setOf("http") + crons.map { "cron:$it" } + queues.map { "queue:$it" } +
        bindings.filter { it.kind == "durable_objects" && it.ownerScript == null }.map { "object:${it.target ?: it.name}" }
    val actualIngress = nodes.mapNotNull { it.attributes["ingress"] }.toSet()
    (expectedIngress - actualIngress).sorted().forEach { findings += "Ingress $it is missing" }
    (actualIngress - expectedIngress).filter { it.startsWith("cron:") || it == "scheduled" }.sorted().forEach {
        findings += "Scheduled ingress $it has no matching cron declaration"
    }
    val clients = nodes.filter { it.attributes["role"] == "client" }
    val connectedClients = clients.count { client ->
        wires.any { it.provider == client.id }.also { if (!it) findings += "Typed client ${client.label} has no consumer wire" }
    }
    val activityNodes = nodes.filter { it.attributes["activity"] != null }
    val activityIds = activityNodes.map { it.attributes.getValue("activity") }.toSet()
    if (requiredActivities == null) findings += "Executable flow inventory has not been declared"
    requiredActivities.orEmpty().minus(activityIds).sorted().forEach { findings += "Activity $it is missing" }
    val expected = requiredActivities.orEmpty()
    return DeploymentCoverage(worker, environment, CoverageCount(if (shape == null) 0 else 1, 1),
        CoverageCount(coveredBindings, declaredBindings.size), CoverageCount(expectedIngress.intersect(actualIngress).size, expectedIngress.size),
        CoverageCount(connectedClients, clients.size), CoverageCount(expected.intersect(activityIds).size, expected.size),
        CoverageCount(activityNodes.count { it.definitionId.isNotEmpty() && it.definitionId in observedNodes }, activityNodes.size),
        findings, shape?.graph?.definitionDigest)
}
