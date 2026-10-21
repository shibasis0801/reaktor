package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.cloudflare.WorkerFleet
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.plugins.WorkerFleetPlugin
import dev.shibasis.dependeasy.web.JavaScriptComponent
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import org.gradle.api.Project
import org.gradle.language.base.plugins.LifecycleBasePlugin

fun Project.workers(
    delivery: Delivery,
    prerequisites: List<OutputHandle> = emptyList(),
    contracts: WorkerContractTests? = null,
    content: WorkerFleet.() -> Unit,
): NodeHandle {
    require(plugins.hasPlugin(WorkerFleetPlugin::class.java)) { "Apply dev.shibasis.dependeasy.worker-fleet" }
    require(this != rootProject) { "Declare workers in their component project" }
    return WorkerFleet(this, delivery.copy(environments = delivery.environments.toList()), prerequisites.toList(), contracts?.copy(environmentFiles = contracts.environmentFiles.toMap()))
        .apply(content).register(BuildWorkspace.get(this))
}

fun Project.workerApp(
    kotlin: List<OutputHandle> = emptyList(),
    packages: List<OutputHandle> = emptyList(),
    typescript: List<String> = listOf("tsconfig.json"),
    tests: PackageScript? = null,
): NodeHandle {
    require(plugins.hasPlugin(WorkerFleetPlugin::class.java)) { "Apply dev.shibasis.dependeasy.worker-fleet" }
    require(this != rootProject) { "Declare the worker application in its component project" }
    val web = JavaScriptComponent(this, name, ".")
    web.kotlinLibraries(*kotlin.toTypedArray())
    web.packages(*packages.toTypedArray())
    web.sources(fileTree(projectDir) {
        include("src/**", "test/**", "scripts/**")
        exclude("**/node_modules/**", "**/build/**", "**/dist/**")
    })
    val verification = web.verify("checkApi", *typescript.toTypedArray(),
        generators = listOf(web.workerTypes()), checks = listOfNotNull(tests?.let { web.check(it.name) }))
    componentRole(Workspace.Verify, verification)
    componentRole(Workspace.Build, verification)
    tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME) { dependsOn(requireNotNull(verification.task)) }
    return verification
}
