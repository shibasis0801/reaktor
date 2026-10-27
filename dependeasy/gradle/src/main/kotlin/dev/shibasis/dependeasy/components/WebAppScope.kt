package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.verification.browserSuite
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

    fun consumes(vararg outputs: OutputHandle) {
        outputs.forEach { output ->
            val files = project.outputFiles(output)
            web.sources(files)
            web.consumedOutputs.add(output)
            web.commands.forEach { BuildWorkspace.get(project).consume(it, output) }
        }
    }

    fun assets(from: OutputHandle, paths: List<WebAsset>) {
        val files = web.dependencyAssets(from, paths)
        BuildWorkspace.get(project).consume(bundle, from)
        requireNotNull(bundle.task).configure {
            inputs.files(files.map { it.values }).withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
        }
    }

    fun generate(script: String, output: String, arguments: List<String> = emptyList(),
                 consumes: List<KernelArtifact<*>> = emptyList()): ArtifactDeclaration<org.gradle.api.file.Directory> = ArtifactDeclaration { name ->
        val task = web.generator(PackageScript(name), script, output, arguments)
        val node = project.node(task).consumes(*consumes.toTypedArray())
        val location = project.layout.projectDirectory.dir(output)
        KernelArtifact(project.providers.provider { location }, node).also {
            bundle.consumes(it)
        }
    }

    fun check(script: String, arguments: List<String> = emptyList()): NodeDeclaration = NodeDeclaration { name ->
        val task = web.generator(PackageScript(name), script, null, arguments)
        task.configure { executionEffect.set(Effect.Verify) }
        project.node(task).also { verification.after(it); bundle.after(it) }
    }

    fun tests(files: FileSet): NodeDeclaration = NodeDeclaration { name ->
        project.node(web.check(files, name)).also {
            verification.after(it)
            BuildWorkspace.get(project).alias(project.projectDir, name, it)
        }
    }

    fun browserTests(suite: Playwright, run: Run = Run.Default): NodeDeclaration = NodeDeclaration { name ->
        project.browserSuite(name, suite, run).after(bundle).covers(Coverage(setOf(Domain.Web, Domain.Site), setOf(Aspect.EndToEnd))).also {
            BuildWorkspace.get(project).alias(project.projectDir, name, it)
        }
    }

    fun performance(spec: WebPerformance): NodeDeclaration = NodeDeclaration { name ->
        project.webPerformance(name, spec, bundle).covers(Aspect.Performance).also { verification.after(it) }
    }
}
