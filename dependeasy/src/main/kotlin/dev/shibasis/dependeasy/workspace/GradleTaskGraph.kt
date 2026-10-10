package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.process.CommandTask
import dev.shibasis.dependeasy.tasks.KotlinCMakeTask
import kotlinx.serialization.json.*
import org.gradle.api.Project
import org.gradle.api.Task

internal fun gradleTaskGraph(project: Project, targets: Collection<String>): List<String> {
    val nodes = linkedMapOf<String, JsonObject>()
    fun visit(task: Task) {
        val owner = task.project.rootProject.name
        val id = owner + task.path
        if (id in nodes) return
        nodes[id] = buildJsonObject { put("task", task.path) }
        val dependencies = runCatching { task.taskDependencies.getDependencies(task).sortedBy { it.path } }
        nodes[id] = buildJsonObject {
            put("build", owner); put("task", task.path)
            put("type", task.javaClass.simpleName.removeSuffix("_Decorated"))
            put("tool", when (task) { is KotlinCMakeTask -> "cmake"; is CommandTask -> task.backend.get(); else -> "gradle" })
            task.group?.let { put("group", it) }
            putJsonArray("requires") { dependencies.getOrNull().orEmpty().forEach { dependency -> add(buildJsonObject {
                put("build", dependency.project.rootProject.name); put("task", dependency.path)
            }) } }
            dependencies.exceptionOrNull()?.let { error ->
                put("unexpanded", generateSequence(error) { it.cause }.map { it.javaClass.simpleName }.joinToString(" / "))
            }
        }
        dependencies.getOrNull().orEmpty().forEach(::visit)
    }
    targets.forEach { visit(project.tasks.named(it).get()) }
    return nodes.values.map { it.toString() }
}
