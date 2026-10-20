package dev.shibasis.dependeasy.handles

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.declarationSite
import dev.shibasis.dependeasy.workspace.kernelRef
import dev.shibasis.dependeasy.workspace.declaredTools
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.TaskProvider
import kotlinx.serialization.encodeToString

fun Project.publishOutput(kind: OutputKind, location: Any, vararg qualifiers: Enum<*>,
    producer: TaskProvider<out Task>? = null) {
    val handle = OutputHandle(dependencies.project(mapOf("path" to path)), rootProject.name, path, kind, qualifiers.map { it.name })
    publishOutput(handle, location, producer, declaredAt = declarationSite()?.takeIf { it.file.endsWith(".gradle.kts") })
}

internal fun Project.publishOutput(handle: OutputHandle, location: Any, producer: TaskProvider<out Task>? = null, declaredAt: SourceSite? = null) {
    val declared = declaredAt ?: declarationSite()
    val files = files(location).also { if (producer != null) it.builtBy(producer) }
    configurations.create(handle.nodeId.name) {
        isCanBeConsumed = true
        isCanBeResolved = false
        attributes.attribute(OutputAttribute, handle.selector)
        outgoing.artifacts(provider { files.files }) { builtBy(files) }
    }
    val workspace = BuildWorkspace.get(this)
    producer?.let {
        val source = node(it)
        val relationship = BuildModelReader.json.encodeToString<Edge>(Edge.Includes(handle.nodeId, source.id))
        workspace.graph.configure { edges.add(relationship) }
    }
    workspace.graph.configure {
        nodes.add(provider {
            val site = declaredAt ?: this@publishOutput.extensions.findByType(SourceSite::class.java) ?: declared
            val tasks = files.buildDependencies.getDependencies(null).sortedBy { it.path }
            require(tasks.isNotEmpty()) { "Output ${handle.nodeId} has no producer" }
            require(tasks.all { it.project.path == this@publishOutput.path && it.project.rootDir == this@publishOutput.rootDir }) { "Publish outputs in their owning project: ${handle.nodeId}; found ${tasks.map { it.project.path }}" }
            BuildModelReader.encode(Node(handle.nodeId, NodeKind.Kernel, handle.nodeId.name,
                when (handle.kind) {
                    OutputKind.VerificationResults -> Effect.Verify
                    OutputKind.WorkspaceCompletion -> when (Workspace.valueOf(handle.qualifiers.single())) {
                        Workspace.Verify -> Effect.Verify
                        Workspace.Deploy -> Effect.Deploy
                        else -> Effect.Build
                    }
                    else -> Effect.Build
                },
                HostRequirement(handle.kind.platform), site, tasks.singleOrNull()?.let(::kernelRef),
                Invocation.Gradle(tasks.map { it.path }), outputs = listOf(FileSet(files.paths(this@publishOutput), handle.kind.name)),
                tools = tasks.flatMap { it.declaredTools() }.distinct(), artifactFamily = handle.kind.family))
        })
        omissions.addAll(provider {
            val site = declaredAt ?: this@publishOutput.extensions.findByType(SourceSite::class.java) ?: declared
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

internal fun Project.publishRole(role: Workspace, target: NodeHandle, manifest: Any) {
    val handle = OutputHandle("", rootProject.name, path, OutputKind.WorkspaceCompletion, listOf(role.name))
    publishOutput(handle, manifest, target.task)
    BuildWorkspace.get(this).graph.configure {
        edges.add(BuildModelReader.json.encodeToString<Edge>(Edge.Includes(handle.nodeId, target.id)))
    }
}

internal fun Project.publishModuleOutput(kind: OutputKind, location: Any, vararg qualifiers: Enum<*>,
    producer: TaskProvider<out Task>? = null) {
    val handle = OutputHandle(dependencies.project(mapOf("path" to path)), rootProject.name, path, kind, qualifiers.map { it.name })
    publishOutput(handle, location, producer)
}

