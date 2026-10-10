package dev.shibasis.dependeasy.model

import kotlinx.serialization.json.*

/** Backend-independent declaration. Gradle and future graph adapters project this model. */
data class BuildPlan(
    val id: String,
    val nodes: List<PlanNode>,
    val targets: Map<String, List<String>>,
    val toolchains: Map<String, String>,
    val build: String = "",
    val project: String = ":",
) {
    fun json(): String = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("schemaVersion", 1)
        put("id", id)
        put("state", "declared")
        put("build", build)
        put("project", project)
        putJsonObject("toolchains") { toolchains.toSortedMap().forEach { (key, value) -> put(key, value) } }
        putJsonArray("nodes") { nodes.forEach { node -> add(buildJsonObject {
            put("id", node.id); put("task", node.task); put("cacheScope", node.cacheScope)
            putJsonArray("requires") { node.requires.sorted().forEach { add(it) } }
        }) } }
        putJsonObject("targets") { targets.toSortedMap().forEach { (target, leaves) ->
            putJsonArray(target) { leaves.sorted().forEach { add(it) } }
        } }
    }) + "\n"
}

data class PlanNode(val id: String, val task: String, val requires: List<String>, val cacheScope: String = "local")
