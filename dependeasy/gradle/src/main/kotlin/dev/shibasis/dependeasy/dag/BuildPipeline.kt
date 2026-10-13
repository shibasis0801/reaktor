package dev.shibasis.dependeasy.dag

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import dev.shibasis.dependeasy.model.BuildPlan
import dev.shibasis.dependeasy.model.PlanNode
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import dev.shibasis.dependeasy.workspace.BuildWorkspace

/** Declaration only: Gradle remains the scheduler and incremental build engine. */
class BuildPipeline internal constructor(private val project: Project, val name: String) {
    private val nodes = linkedMapOf<String, BuildNode>()
    private val prerequisites = linkedMapOf<String, MutableSet<String>>()
    private val targets = linkedMapOf<String, List<String>>()

    fun node(task: TaskProvider<out Task>, id: String = task.name): BuildNode {
        val existing = nodes[id]
        require(existing == null || existing.task == task) {
            "Pipeline '$name' already has node '$id'; use a project-qualified ID for foreign tasks"
        }
        return existing ?: BuildNode(task, id, this).also { nodes[id] = it }
    }

    internal fun connect(node: BuildNode, prerequisite: BuildNode) {
        require(nodes[node.id] === node && nodes[prerequisite.id] === prerequisite) {
            "Both nodes must belong to pipeline '$name'"
        }
        val from = node.id
        val to = prerequisite.id
        fun reaches(current: String, target: String, visited: MutableSet<String>): Boolean =
            current == target || (visited.add(current) && prerequisites[current].orEmpty().any {
                reaches(it, target, visited)
            })
        if (reaches(to, from, mutableSetOf())) {
            throw GradleException("Pipeline '$name' has a cycle: $from -> $to -> $from")
        }
        prerequisites.getOrPut(from) { linkedSetOf() }.add(to)
    }

    /** An explicit entrypoint; unrelated backend work is never attached to `help`. */
    fun target(taskName: String, vararg leaves: BuildNode): TaskProvider<Task> {
        require(leaves.all { nodes[it.id] === it }) { "Target '$taskName' must use nodes from pipeline '$name'" }
        targets[taskName] = leaves.map { it.id }
        return project.tasks.register(taskName) {
            group = "dependeasy"
            description = "Build pipeline ${this@BuildPipeline.name}"
            dependsOn(leaves.map { it.task })
        }
    }

    fun report(taskName: String = "${name}BuildPlan"): TaskProvider<PipelineReport> =
        project.tasks.register(taskName, PipelineReport::class.java) {
            group = "dependeasy"
            description = "Show pipeline ${this@BuildPipeline.name} without running its tools"
            stages.set(describe())
            plan.set(model().json())
            outputFile.set(project.layout.buildDirectory.file("dependeasy/plans/$name.json"))
        }.also { BuildWorkspace.get(project).include(it) }

    fun model(): BuildPlan {
        val prefix = "${project.rootProject.name}${project.path}/$name"
        fun qualify(id: String) = "$prefix/$id"
        return BuildPlan(prefix, nodes.values.map { node ->
            PlanNode(qualify(node.id), node.task.get().path, prerequisites[node.id].orEmpty().map(::qualify))
        }, targets.mapValues { (_, leaves) -> leaves.map(::qualify) }, mapOf(
            "kotlin" to ToolchainVersions.Kotlin, "node" to ToolchainVersions.Node,
            "pnpm" to ToolchainVersions.Pnpm, "typescript" to ToolchainVersions.TypeScript,
            "karakum" to ToolchainVersions.Karakum,
            "cmake" to ToolchainVersions.Cmake, "swift.minimum" to ToolchainVersions.Swift), project.rootProject.name, project.path)
    }

    fun describe(): List<String> = nodes.keys.map { task ->
        "$task <- ${prerequisites[task].orEmpty().joinToString(", ")}".trimEnd()
    }
}
