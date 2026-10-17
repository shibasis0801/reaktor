package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.handles.OutputHandle
import dev.shibasis.dependeasy.handles.output
import dev.shibasis.dependeasy.model.*
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

open class Parts internal constructor(internal val roles: Map<Workspace, List<OutputHandle>>)

class Product internal constructor(
    val shared: Parts,
    val clients: Parts,
    val services: Parts,
    val sites: Parts,
    val tools: Parts,
    val packages: Parts,
    val native: Parts,
) : Parts(combine(listOf(shared, clients, services, sites, tools, packages, native)))

@WorkspaceDsl
class ProductScope internal constructor(private val project: Project, private val shared: Parts) {
    private var clients = emptyParts()
    private var services = emptyParts()
    private var sites = emptyParts()
    private var tools = emptyParts()
    private var packages = emptyParts()
    private var native = emptyParts()

    fun clients(vararg parts: ProjectDependency) { clients = Parts(combine(listOf(clients, project.parts(parts.toList())))) }
    fun services(vararg parts: ProjectDependency) { services = Parts(combine(listOf(services, project.parts(parts.toList())))) }
    fun sites(vararg parts: ProjectDependency) { sites = Parts(combine(listOf(sites, project.parts(parts.toList())))) }
    fun tools(vararg parts: ProjectDependency) { tools = Parts(combine(listOf(tools, project.parts(parts.toList())))) }
    fun tools(vararg builds: BuildHandle) { tools = Parts(combine(listOf(tools, Parts(Workspace.entries.associateWith { role -> builds.map { output(it, role) } })))) }
    fun packages(vararg modules: ModuleHandle) { packages = Parts(combine(listOf(packages, parts(modules.toList())))) }
    fun native(vararg modules: ModuleHandle) { native = Parts(combine(listOf(native, parts(modules.toList())))) }

    internal fun build() = Product(shared, clients, services, sites, tools, packages, native)
}

class IncludedBuild internal constructor(build: BuildHandle) {
    val verify = output(build, Workspace.Verify)
    val artifacts = output(build, Workspace.Build)
}

internal fun Project.parts(projects: List<ProjectDependency>) = Parts(Workspace.entries.associateWith { role ->
    projects.map { OutputHandle(it, rootProject.name, it.path, OutputKind.WorkspaceCompletion, listOf(role.name)) }
})

internal fun parts(modules: List<ModuleHandle>) = Parts(Workspace.entries.associateWith { role ->
    modules.map { OutputHandle(it.coordinate, it.build.name, it.path, OutputKind.WorkspaceCompletion, listOf(role.name)) }
})

internal fun emptyParts() = Parts(emptyMap())

private fun combine(parts: List<Parts>) = Workspace.entries.associateWith { role ->
    parts.flatMap { it.roles[role].orEmpty() }.distinctBy { it.nodeId }
}
