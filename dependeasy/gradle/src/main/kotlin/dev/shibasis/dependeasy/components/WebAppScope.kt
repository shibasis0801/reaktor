package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.web.JavaScriptComponent
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.NodeDeclaration
import org.gradle.api.Project

@DslMarker
annotation class ComponentDsl

@ComponentDsl
class WebAppScope internal constructor(
    private val project: Project,
    private val web: JavaScriptComponent,
    val build: NodeHandle,
    val bundle: NodeHandle,
    val typecheck: NodeHandle,
    private val verification: NodeHandle,
) {
    fun alias(script: PackageScript, node: NodeHandle) =
        BuildWorkspace.get(project).alias(project.rootProject.file(script.directory), script.name, node)

    fun packages(vararg outputs: OutputHandle, directory: String = ".") = web.packages(*outputs, directory = directory)

    fun assets(from: OutputHandle, paths: List<WebAsset>) {
        val files = web.dependencyAssets(from, paths)
        BuildWorkspace.get(project).consume(bundle, from)
        requireNotNull(bundle.task).configure {
            inputs.files(files.map { it.values }).withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
        }
    }

    fun performance(spec: WebPerformance): NodeDeclaration = NodeDeclaration { name ->
        project.webPerformance(name, spec, bundle).covers(Aspect.Performance).also { verification.after(it) }
    }
}
