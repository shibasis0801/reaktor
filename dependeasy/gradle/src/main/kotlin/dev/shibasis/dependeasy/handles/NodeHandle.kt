package dev.shibasis.dependeasy.handles

import dev.shibasis.dependeasy.model.NodeId
import dev.shibasis.dependeasy.model.Invocation
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

class NodeHandle internal constructor(val id: NodeId, val task: TaskProvider<out Task>?, val outputKind: dev.shibasis.dependeasy.model.OutputKind? = null,
    internal val outputKinds: () -> List<dev.shibasis.dependeasy.model.OutputKind> = { listOfNotNull(outputKind) },
    private val owner: Project? = null,
    internal val invocation: () -> Invocation) {
    val name get() = id.name

    fun after(vararg prerequisites: NodeHandle): NodeHandle = apply {
        dev.shibasis.dependeasy.workspace.BuildWorkspace.get(requireNotNull(owner)).connect(this, prerequisites.toList())
    }

    fun consumes(vararg artifacts: KernelArtifact<*>): NodeHandle = apply {
        val project = requireNotNull(owner)
        artifacts.forEach { artifact ->
            dev.shibasis.dependeasy.workspace.BuildWorkspace.get(project).consume(this, artifact.producer, artifact.location)
        }
    }
}

fun Project.node(task: TaskProvider<out Task>, kind: dev.shibasis.dependeasy.model.OutputKind? = null): NodeHandle {
    val id = NodeId(rootProject.name, path, task.name)
    dev.shibasis.dependeasy.workspace.BuildWorkspace.get(this).reference(this, task, kind)
    return NodeHandle(id, task, kind, owner = this) { Invocation.Gradle(listOf(path.trimEnd(':') + ":" + task.name)) }
}
