package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.configuration.resolve
import dev.shibasis.dependeasy.handles.NodeHandle
import dev.shibasis.dependeasy.handles.OutputHandle
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.verification.karateSuite
import dev.shibasis.dependeasy.verification.k6Suite
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

@DslMarker
annotation class WorkspaceDsl

class NodeDeclaration internal constructor(private val declare: (String) -> NodeHandle) {
    operator fun provideDelegate(owner: Any?, property: KProperty<*>): ReadOnlyProperty<Any?, NodeHandle> {
        val node = declare(property.name)
        return ReadOnlyProperty { _, _ -> node }
    }
}

@WorkspaceDsl
class WorkspaceScope internal constructor(private val project: Project, private val workspace: BuildWorkspace) {
    val model get() = workspace.graph.flatMap { it.outputFile }

    fun alias(name: String, node: NodeHandle, directory: java.io.File = project.rootDir, options: DeviceOptions? = null,
        before: List<NodeHandle> = emptyList(), settings: List<OptionArgument<*>> = emptyList()) =
        workspace.alias(directory, name, node, options, before, settings)

    fun packageScripts() = workspace.packageScripts()

    fun dagger(content: dev.shibasis.dependeasy.dagger.DaggerModule.() -> Unit = {}) =
        workspace.dagger(content)

    fun workers(content: dev.shibasis.dependeasy.cloudflare.WorkerFleet.() -> Unit) = workspace.workers(content)

    fun deviceFlows(content: dev.shibasis.dependeasy.verification.DeviceFlows.() -> Unit) =
        NodeDeclaration { name -> dev.shibasis.dependeasy.verification.DeviceFlows(project, name).apply(content).register() }

    fun karateSuite(directory: String, config: String, tags: List<String> = emptyList(), environmentFiles: List<String> = emptyList()) =
        NodeDeclaration { name -> project.karateSuite(name, directory, config, tags, environmentFiles) }

    fun k6Suite(script: String, environmentFiles: List<String> = emptyList()) =
        NodeDeclaration { name -> project.k6Suite(name, script, environmentFiles) }

    fun external(call: ExternalCall, removal: String, run: Run = Run.Default,
        runner: Runner = when (call) { is ExternalCall.Fastlane -> Runner.Fastlane; is ExternalCall.Package -> Runner.Pnpm; is ExternalCall.BashScript -> Runner.Shell }, content: ExternalSpec.() -> Unit = {}): NodeDeclaration {
        require(removal.isNotBlank()) { "An external call needs its kernel replacement condition" }
        val declaration = NodeContent(project, run)
        ExternalSpec(declaration).apply(content)
        val site = project.declarationSite()
        return NodeDeclaration { name ->
            declaration.includes.add(project.externalCommand("${name}Command", call, removal,
                if (call is ExternalCall.Fastlane) Effect.Build else Effect.Verify, run))
            workspace.emit(project, declaration.spec(name, NodeKind.External).copy(
                effect = if (call is ExternalCall.Fastlane) Effect.Build else Effect.Verify,
                runner = runner), WorkspaceCompletion, site, null,
                declaration.includes.toList(), declaration.consumes.toList(), declaration.after.toList())
        }
    }

    fun product(shared: ProjectDependency, content: ProductScope.() -> Unit = {}) =
        ProductScope(project, project.parts(listOf(shared))).apply(content).build()

    fun product(modules: List<ModuleHandle>, content: ProductScope.() -> Unit = {}) =
        ProductScope(project, parts(modules)).apply(content).build()

    fun include(build: BuildHandle): IncludedBuild {
        workspace.includedBuild(build)
        return IncludedBuild(build)
    }

    fun includeIf(option: BuildOption<Boolean>, build: BuildHandle): IncludedBuild? =
        if (project.resolve(option).get().value) include(build) else null

    fun verify(parts: Parts, role: Workspace? = null, run: Run = Run.Default, content: VerifySpec.() -> Unit = {}) =
        verification(parts.roles[Workspace.Verify].orEmpty(), role, run, content)

    fun verify(part: ProjectDependency, role: Workspace? = null, run: Run = Run.Default, content: VerifySpec.() -> Unit = {}) =
        verify(project.parts(listOf(part)), role, run, content)

    fun verify(role: Workspace? = null, run: Run = Run.Default, content: VerifySpec.() -> Unit = {}) =
        verification(emptyList(), role, run, content)

    private fun verification(outputs: List<OutputHandle>, role: Workspace?, run: Run, content: VerifySpec.() -> Unit): NodeDeclaration {
        val declaration = NodeContent(project, run).apply { consumes.addAll(outputs) }
        VerifySpec(declaration).apply(content)
        return emit(declaration, NodeKind.Verification, role, project.declarationSite(), WorkspaceCompletion)
    }

    fun artifacts(parts: Parts, role: Workspace? = null, run: Run = Run.Default, content: ArtifactsSpec.() -> Unit = {}) =
        artifacts(parts.roles[Workspace.Build].orEmpty(), role, run, content)

    fun artifacts(part: ProjectDependency, role: Workspace? = null, run: Run = Run.Default, content: ArtifactsSpec.() -> Unit = {}) =
        artifacts(project.parts(listOf(part)), role, run, content)

    fun artifacts(role: Workspace? = null, run: Run = Run.Default, content: ArtifactsSpec.() -> Unit = {}) =
        artifacts(emptyList(), role, run, content)

    private fun artifacts(outputs: List<OutputHandle>, role: Workspace?, run: Run, content: ArtifactsSpec.() -> Unit): NodeDeclaration {
        val declaration = NodeContent(project, run).apply { consumes.addAll(outputs) }
        ArtifactsSpec(declaration).apply(content)
        return emit(declaration, NodeKind.ArtifactSet, role, project.declarationSite(), WorkspaceCompletion)
    }

    fun delivery(after: NodeHandle, run: Run = Run.Default, content: DeliveryScope.() -> Unit) {
        DeliveryScope(project, workspace, after, run).apply(content)
    }

    private fun emit(content: NodeContent, kind: NodeKind, role: Workspace?, site: SourceSite?, kernel: WorkspaceCompletion) =
        NodeDeclaration { name -> workspace.emit(project, content.spec(name, kind), kernel, site, role, content.includes.toList(), content.consumes.toList(), content.after.toList()) }
}

@WorkspaceDsl
class DeliveryScope internal constructor(
    private val project: Project,
    private val workspace: BuildWorkspace,
    private val gate: NodeHandle,
    private val run: Run,
) {
    fun release(output: OutputHandle, lane: LaneHandle, run: Run = this.run, content: ReleaseSpec.() -> Unit = {}): NodeDeclaration {
        val declaration = NodeContent(project, run).apply { consumes.add(output); after.add(gate) }
        ReleaseSpec(declaration).apply(content)
        val site = project.declarationSite()
        return NodeDeclaration { name ->
            declaration.includes.add(project.externalCommand("${name}Lane", ExternalCall.Fastlane(lane), "WP16 typed store release kernel", Effect.Deploy, run))
            workspace.emit(project, declaration.spec(name, NodeKind.Release), WorkspaceCompletion, site, null,
                declaration.includes.toList(), declaration.consumes.toList(), declaration.after.toList())
        }
    }

    fun deploy(worker: WorkerRef, producer: NodeHandle, environments: List<Environment>, run: Run = this.run,
        content: DeploySpec.() -> Unit = {}): NodeDeclaration {
        val declaration = NodeContent(project, run).apply { includes.add(producer); after.add(gate) }
        DeploySpec(declaration).apply(content)
        val site = project.declarationSite()
        return NodeDeclaration { name -> workspace.emit(project, declaration.spec(name, NodeKind.Deploy).copy(worker = worker, environments = environments),
            WorkspaceCompletion, site, null, declaration.includes.toList(), emptyList(), declaration.after.toList()) }
    }

    fun migrate(output: OutputHandle, run: Run = this.run, content: DeploySpec.() -> Unit = {}): NodeDeclaration {
        require(output.kind == OutputKind.DatabaseMigration) { "A migration needs a component's database migration output" }
        return deploy(output, run, content)
    }

    fun publish(output: OutputHandle, run: Run = this.run, content: ReleaseSpec.() -> Unit = {}): NodeDeclaration {
        require(output.kind == OutputKind.PublishedModules) { "A publication needs a component's published modules output" }
        val declaration = NodeContent(project, run).apply { consumes.add(output); after.add(gate) }
        ReleaseSpec(declaration).apply(content)
        val site = project.declarationSite()
        return NodeDeclaration { name -> workspace.emit(project, declaration.spec(name, NodeKind.Release), WorkspaceCompletion, site, null,
            emptyList(), declaration.consumes.toList(), declaration.after.toList()) }
    }

    fun deploy(parts: Parts, run: Run = this.run, content: DeploySpec.() -> Unit = {}) =
        deploy(parts.roles[Workspace.Deploy].orEmpty(), run, content)

    fun deploy(part: ProjectDependency, run: Run = this.run, content: DeploySpec.() -> Unit = {}) =
        deploy(project.parts(listOf(part)), run, content)

    fun deploy(output: OutputHandle, run: Run = this.run, content: DeploySpec.() -> Unit = {}) =
        deploy(listOf(output), run, content)

    private fun deploy(outputs: List<OutputHandle>, run: Run, content: DeploySpec.() -> Unit): NodeDeclaration {
        require(outputs.isNotEmpty()) { "A deployment needs a component's declared deploy output" }
        val declaration = NodeContent(project, run).apply { consumes.addAll(outputs); after.add(gate) }
        DeploySpec(declaration).apply(content)
        val site = project.declarationSite()
        return NodeDeclaration { name -> workspace.emit(project, declaration.spec(name, NodeKind.Deploy), WorkspaceCompletion, site, null,
            declaration.includes.toList(), declaration.consumes.toList(), declaration.after.toList()) }
    }
}

@WorkspaceDsl
class VerifySpec internal constructor(private val content: NodeContent) {
    fun include(node: NodeHandle, vararg others: NodeHandle) { content.includes.addAll(listOf(node) + others) }
    fun include(output: OutputHandle, vararg others: OutputHandle) { content.consumes.addAll(listOf(output) + others) }
    fun after(vararg nodes: NodeHandle) { content.after.addAll(nodes) }
    fun <T : Any> require(option: BuildOption<T>, value: T) { content.require(option, value) }
}

@WorkspaceDsl
class ArtifactsSpec internal constructor(private val content: NodeContent) {
    fun outputs(vararg outputs: OutputHandle) { content.consumes.addAll(outputs) }
    fun include(vararg nodes: NodeHandle) { content.includes.addAll(nodes) }
}

@WorkspaceDsl
class DeploySpec internal constructor(private val content: NodeContent) {
    fun after(vararg nodes: NodeHandle) { content.after.addAll(nodes) }
    fun <T : Any> require(option: BuildOption<T>, value: T) { content.require(option, value) }
}

@WorkspaceDsl
class ReleaseSpec internal constructor(private val content: NodeContent) {
    fun after(vararg nodes: NodeHandle) { content.after.addAll(nodes) }
    fun <T : Any> require(option: BuildOption<T>, value: T) { content.require(option, value) }
}

@WorkspaceDsl
class ExternalSpec internal constructor(private val content: NodeContent) {
    fun include(vararg nodes: NodeHandle) { content.includes.addAll(nodes) }
    fun after(vararg nodes: NodeHandle) { content.after.addAll(nodes) }
    fun <T : Any> require(option: BuildOption<T>, value: T) { content.require(option, value) }
}

internal class NodeContent(private val project: Project, private val run: Run) {
    val includes = linkedSetOf<NodeHandle>()
    val consumes = linkedSetOf<OutputHandle>()
    val after = linkedSetOf<NodeHandle>()
    private val requirements = mutableListOf<OptionRequirement>()

    fun <T : Any> require(option: BuildOption<T>, value: T) {
        requirements.add(OptionRequirement(project.resolve(option).get().evidence, value.toString()))
    }

    fun spec(name: String, kind: NodeKind) = KernelSpec.WorkspaceNode(NodeId(project.rootProject.name, project.path, name), kind, run,
        includes.map { it.id }, consumes.map { it.nodeId }.distinct(), after.map { it.id }, requirements.toList())
}
