package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.handles.OutputAttribute
import dev.shibasis.dependeasy.handles.OutputHandle
import dev.shibasis.dependeasy.handles.output
import dev.shibasis.dependeasy.model.*
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

open class Parts internal constructor(internal val roles: (Workspace) -> List<OutputHandle>)

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
    fun tools(vararg builds: BuildHandle) { tools = Parts(combine(listOf(tools, Parts { role -> if (role == Workspace.Build || role == Workspace.Verify) builds.map { output(it, role) } else emptyList() }))) }
    fun packages(vararg parts: ProjectDependency) { packages = Parts(combine(listOf(packages, project.parts(parts.toList())))) }
    fun packages(vararg modules: ModuleHandle) { packages = Parts(combine(listOf(packages, project.parts(modules.toList())))) }
    fun native(vararg modules: ModuleHandle) { native = Parts(combine(listOf(native, project.parts(modules.toList())))) }

    internal fun build() = Product(shared, clients, services, sites, tools, packages, native)
}

class IncludedBuild internal constructor(build: BuildHandle) {
    val verify = output(build, Workspace.Verify)
    val artifacts = output(build, Workspace.Build)
}

internal fun Project.parts(projects: List<ProjectDependency>) = Parts { role ->
    projects.mapNotNull { dependency ->
        val output = OutputHandle(dependency, rootProject.name, dependency.path, OutputKind.WorkspaceCompletion, listOf(role.name))
        val owner = rootProject.project(dependency.path)
        owner.configurations.findByName(output.nodeId.name)?.let { published ->
            require(published.isCanBeConsumed && published.attributes.getAttribute(OutputAttribute) == output.selector) {
                "Invalid component role: ${output.nodeId}"
            }
            output
        }
    }
}

@JvmName("moduleParts")
internal fun Project.parts(modules: List<ModuleHandle>): Parts {
    require(modules.all { it.build.name == rootProject.name }) { "Compose components in their owning build; include another build through its declared workspace roles" }
    return parts(modules.map { dependencies.project(mapOf("path" to it.path)) as ProjectDependency })
}

internal fun emptyParts() = Parts { emptyList() }

private fun combine(parts: List<Parts>): (Workspace) -> List<OutputHandle> = { role ->
    parts.flatMap { it.roles(role) }.distinctBy { it.nodeId }
}
