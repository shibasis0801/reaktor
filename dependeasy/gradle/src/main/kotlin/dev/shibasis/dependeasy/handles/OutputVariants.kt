package dev.shibasis.dependeasy.handles

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.declarationSite
import dev.shibasis.dependeasy.workspace.kernelRef
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.TaskProvider
import kotlinx.serialization.encodeToString

fun Project.publishOutput(kind: OutputKind, location: Any, vararg qualifiers: Enum<*>,
    producer: TaskProvider<out Task>? = null) {
    val handle = OutputHandle(dependencies.project(mapOf("path" to path)), rootProject.name, path, kind, qualifiers.map { it.name })
    publishOutput(handle, location, producer)
}

internal fun Project.publishOutput(handle: OutputHandle, location: Any, producer: TaskProvider<out Task>? = null) {
    val site = declarationSite()
    val files = files(location).also { if (producer != null) it.builtBy(producer) }
    configurations.create(handle.nodeId.name) {
        isCanBeConsumed = true
        isCanBeResolved = false
        attributes.attribute(OutputAttribute, handle.selector)
        outgoing.artifacts(provider { files.files }) { builtBy(files) }
    }
    BuildWorkspace.get(this).graph.configure {
        nodes.add(provider {
            val tasks = files.buildDependencies.getDependencies(null).sortedBy { it.path }
            require(tasks.isNotEmpty()) { "Output ${handle.nodeId} has no producer" }
            require(tasks.all { it.project.path == this@publishOutput.path && it.project.rootDir == this@publishOutput.rootDir }) { "Publish outputs in their owning project: ${handle.nodeId}; found ${tasks.map { it.project.path }}" }
            BuildModelReader.encode(Node(handle.nodeId, NodeKind.Kernel, handle.nodeId.name,
                if (handle.kind in setOf(OutputKind.VerificationResults, OutputKind.WorkspaceCompletion)) Effect.Verify else Effect.Build,
                HostRequirement(handle.kind.platform), site, tasks.singleOrNull()?.let(::kernelRef),
                Invocation.Gradle(tasks.map { it.path }), outputs = listOf(FileSet(files.paths(this@publishOutput), handle.kind.name)),
                artifactFamily = handle.kind.family))
        })
        omissions.addAll(provider {
            val tasks = files.buildDependencies.getDependencies(null)
            listOfNotNull(if (tasks.size != 1) Omission(handle.nodeId, "Output has multiple Gradle producers", tasks.map { it.path }.sorted().joinToString(", ")) else null,
                if (site == null) Omission(handle.nodeId, "Output is declared by its owning plugin") else null)
                .map { BuildModelReader.json.encodeToString(it) }
        })
    }
}

internal fun FileCollection.paths(project: Project) = files.map {
    it.absoluteFile.relativeTo(project.rootDir.absoluteFile).invariantSeparatorsPath
}.sorted()

internal fun Project.publishRole(role: Workspace, target: NodeHandle, manifest: Any) =
    publishOutput(OutputHandle("", rootProject.name, path, OutputKind.WorkspaceCompletion, listOf(role.name)), manifest, target.task)
