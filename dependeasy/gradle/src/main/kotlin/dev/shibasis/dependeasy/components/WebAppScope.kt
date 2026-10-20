package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.web.JavaScriptComponent
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.NodeDeclaration
import dev.shibasis.dependeasy.workspace.declarationSite
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
    private var deploymentDeclared = false

    fun alias(script: PackageScript, node: NodeHandle) =
        BuildWorkspace.get(project).alias(project.rootProject.file(script.directory), script.name, node)

    fun assets(from: OutputHandle, paths: List<WebAsset>) {
        val files = web.dependencyAssets(from, paths)
        requireNotNull(bundle.task).configure {
            inputs.files(files.map { it.values }).withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
        }
    }

    fun performance(spec: WebPerformance): NodeDeclaration = NodeDeclaration { name ->
        project.webPerformance(name, spec, bundle).also { verification.after(it) }
    }

    fun deploy(worker: WorkerRef, environments: List<Environment>, configuration: String = "wrangler.json"): NodeDeclaration {
        require(environments.isNotEmpty()) { "Declare the web application's deployment environments" }
        require(project.rootProject.file(worker.directory).canonicalFile == project.projectDir.canonicalFile) {
            "The web application owns its deployment: ${worker.directory}"
        }
        val site = project.declarationSite()
        return NodeDeclaration { name ->
            check(!deploymentDeclared) { "A web application declares one deployment" }
            deploymentDeclared = true
            val command = web.deploy(configuration, build)
            val execution = project.node(command)
            val spec = KernelSpec.WorkspaceNode(NodeId(project.rootProject.name, project.path, name), NodeKind.Deploy,
                Run.onLinux().onWorker(), listOf(execution.id), emptyList(), emptyList(), emptyList(),
                worker = worker, environments = environments)
            val result = BuildWorkspace.get(project).emit(project, spec, WorkspaceCompletion, site, null,
                listOf(execution), emptyList(), emptyList())
            project.publishRole(Workspace.Deploy, execution, command.flatMap { it.receiptFile })
            result
        }
    }
}
