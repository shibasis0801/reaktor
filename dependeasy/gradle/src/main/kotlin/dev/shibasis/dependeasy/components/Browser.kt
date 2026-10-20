package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.common.KotlinDependencies
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.BuildOption
import dev.shibasis.dependeasy.model.SourceSite
import dev.shibasis.dependeasy.model.Workspace
import dev.shibasis.dependeasy.plugins.BrowserPlugin
import dev.shibasis.dependeasy.web.KotlinBrowser
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.WorkspaceScope
import dev.shibasis.dependeasy.workspace.declarationSite
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

fun Project.browserApp(output: String = "dist/client", moduleName: String = "${rootProject.name}-$name", content: BrowserScope.() -> Unit = {}) {
    require(plugins.hasPlugin(BrowserPlugin::class.java)) { "Apply dev.shibasis.dependeasy.browser" }
    declarationSite()?.let { extensions.add(SourceSite::class.java, "dependeasyComponentDeclaration", it) }
    BrowserScope(this, KotlinBrowser(this, output, moduleName)).apply(content).bind()
    val part = dependencies.project(mapOf("path" to path)) as ProjectDependency
    WorkspaceScope(this, BuildWorkspace.get(this)).apply {
        val componentBuild by artifacts(role = Workspace.Build) { outputs(moduleBuild(part)) }
    }
}

@ComponentDsl
class BrowserScope internal constructor(private val project: Project, private val browser: KotlinBrowser) {
    private val dependencyBlocks = mutableListOf<KotlinDependencies.() -> Unit>()
    private val resourceDirectories = mutableListOf<String>()
    private val resourceOutputs = mutableListOf<OutputHandle>()

    fun dependencies(content: KotlinDependencies.() -> Unit) { dependencyBlocks.add(content) }
    fun resources(directory: String) { resourceDirectories.add(directory) }
    fun resources(output: OutputHandle) {
        require(output.kind == dev.shibasis.dependeasy.model.OutputKind.WebResources) { "Browser resources need a web resource output" }
        resourceOutputs.add(output)
    }
    fun <T : Any> require(option: BuildOption<T>, value: T) = browser.require(option, value)

    internal fun bind() {
        val bundle = project.node(browser.register())
        val source = project.extensions.getByType<KotlinMultiplatformExtension>().sourceSets.getByName("jsMain")
        source.resources.srcDirs(resourceDirectories)
        resourceOutputs.forEach { output ->
            source.resources.srcDir(project.outputFiles(output))
            BuildWorkspace.get(project).consume(bundle, output)
        }
        source.dependencies { dependencyBlocks.forEach { KotlinDependencies(this).apply(it) } }
    }
}
