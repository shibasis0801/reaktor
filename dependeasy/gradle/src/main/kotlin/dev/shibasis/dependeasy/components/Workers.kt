package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.cloudflare.WorkerFleet
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.plugins.WorkerFleetPlugin
import dev.shibasis.dependeasy.web.JavaScriptComponent
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.declarationSite
import org.gradle.api.Project
import org.gradle.language.base.plugins.LifecycleBasePlugin

fun Project.workers(
    environments: List<Environment>,
    prerequisites: List<OutputHandle> = emptyList(),
    contracts: WorkerContractTests? = null,
    content: WorkerFleet.() -> Unit,
): NodeHandle {
    require(plugins.hasPlugin(WorkerFleetPlugin::class.java)) { "Apply dev.shibasis.dependeasy.worker-fleet" }
    require(this != rootProject) { "Declare workers in their component project" }
    return WorkerFleet(this, environments.toList(), prerequisites.toList(), contracts?.copy(environmentFiles = contracts.environmentFiles.toMap()))
        .apply(content).register(BuildWorkspace.get(this))
}

fun Project.workerApp(
    worker: WorkerRef,
    environments: List<Environment>,
    kotlin: List<OutputHandle> = emptyList(),
    typescript: List<String> = listOf("tsconfig.json"),
    tests: PackageScript? = null,
): NodeHandle {
    require(plugins.hasPlugin(WorkerFleetPlugin::class.java)) { "Apply dev.shibasis.dependeasy.worker-fleet" }
    require(this != rootProject && rootProject.file(worker.directory).canonicalFile == projectDir.canonicalFile) {
        "The worker application owns its deployment: ${worker.directory}"
    }
    require(environments.isNotEmpty()) { "Declare the worker application's deployment environments" }
    val spec = WorkerAppSpec(worker, environments.toList(), typescript.toList(), tests)
    val web = JavaScriptComponent(this, name, ".")
    web.kotlinLibraries(*kotlin.toTypedArray())
    web.sources(fileTree(projectDir) {
        include("src/**", "test/**", "scripts/**")
        exclude("**/node_modules/**", "**/build/**", "**/dist/**")
    })
    val verification = web.verify("checkApi", *spec.typescript.toTypedArray(),
        generators = listOf(web.workerTypes()), checks = listOfNotNull(spec.tests?.let { web.check(it.name) }))
    componentRole(Workspace.Verify, verification)
    componentRole(Workspace.Build, verification)
    tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME) { dependsOn(requireNotNull(verification.task)) }
    val execution = node(web.deploy("wrangler.json", verification))
    val declaration = KernelSpec.WorkspaceNode(NodeId(rootProject.name, path, "publishWorker"), NodeKind.Deploy,
        Run.onLinux().onWorker(), listOf(execution.id), emptyList(), emptyList(), emptyList(),
        worker = spec.worker, environments = spec.environments)
    return BuildWorkspace.get(this).emit(this, declaration, DeployKernel, declarationSite(), null,
        listOf(execution), emptyList(), emptyList()).also { componentRole(Workspace.Deploy, it) }
}
