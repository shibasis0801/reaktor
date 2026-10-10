package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.dag.PipelineReport
import dev.shibasis.dependeasy.dagger.DaggerModule
import dev.shibasis.dependeasy.cloudflare.WorkerFleet
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

class BuildWorkspace internal constructor(private val project: Project) {
    init { project.reportBuildLayout() }
    private val targets = linkedMapOf<String, BuildTarget>()
    val graph = project.tasks.register<BuildGraphTask>("buildGraph") {
        group = "dependeasy"
        description = "Export declared build targets and pipelines without executing their tools"
        buildName.set(project.name)
        this.targets.convention(emptyList())
        // Resolve while configuring the selected graph task, before execution holds build locks.
        gradleTasks.set(gradleTaskGraph(project,
            this@BuildWorkspace.targets.filterValues { it.invocation.isEmpty() && it.recipes.isEmpty() }.keys))
        workspaceDirectory.set(project.layout.projectDirectory)
        packageManifests.from(dev.shibasis.dependeasy.web.workspaceManifests(project).matching {
            exclude("**/build/**", "**/ts/export/**", "**/worker/app-kt/**", "**/worker/mcp-kt/**")
        })
        outputFile.set(project.layout.buildDirectory.file("dependeasy/build-graph.json"))
    }

    fun target(name: String, configure: BuildTarget.() -> Unit): TaskProvider<Task> {
        require(name.matches(Regex("[a-z][A-Za-z0-9]*"))) { "Use a camelCase target name: $name" }
        require(name !in targets) { "Duplicate build target: $name" }
        val target = BuildTarget(project, name).apply(configure)
        require(target.invocation.isEmpty() && target.recipes.isEmpty()) { "Declare external invocations with external() so they cannot recursively execute Gradle" }
        targets[name] = target
        graph.configure { this.targets.add(project.provider { target.json() }) }
        return project.tasks.register(name) {
            group = "dependeasy"
            dependsOn(target.dependencies)
        }
    }

    fun external(name: String, configure: BuildTarget.() -> Unit) {
        require(name !in targets) { "Duplicate build target: $name" }
        val target = BuildTarget(project, name).apply(configure)
        require(target.invocation.isNotEmpty() || target.recipes.isNotEmpty()) { "External target '$name' needs a command or recipe" }
        targets[name] = target
        graph.configure { this.targets.add(project.provider { target.json() }) }
    }

    fun verify(name: String, vararg paths: String, configure: BuildTarget.() -> Unit = {}) =
        target(name) { effect = "verify"; tasks(*paths); configure() }

    fun artifacts(name: String, vararg paths: String, configure: BuildTarget.() -> Unit = {}) =
        target(name) { tasks(*paths); configure() }

    fun dagger(directory: Any = ".dagger", configure: DaggerModule.() -> Unit = {}) =
        DaggerModule(project, targets).apply(configure).register(directory)

    fun workers(configure: WorkerFleet.() -> Unit) = WorkerFleet(project).apply(configure).register(this)

    fun includedBuild(name: String) {
        val build = project.gradle.includedBuild(name)
        graph.configure {
            includedGraphs.from(project.files(build.projectDir.resolve("build/dependeasy/build-graph.json"))
                .builtBy(build.task(":buildGraph")))
        }
    }

    fun packageScripts() = project.tasks.register<PackageScriptsTask>("generatePackageScripts") {
        group = "code generation"
        val file = project.layout.projectDirectory.file("package.json")
        val generated = this@BuildWorkspace.targets.filterValues { it.worker != null }.mapValues { ":${it.key}" }
        manifest.set(project.providers.fileContents(file).asText.map { authoredManifest(it, generated.keys) })
        aliases.set(generated)
        targets.set(generated.keys.associateWith { this@BuildWorkspace.targets.getValue(it).json() })
        outputFile.set(file)
        generatedFile.set(project.layout.buildDirectory.file("dependeasy/generated-package-scripts.json"))
    }.also { generation ->
        graph.configure { mustRunAfter(generation) }
        project.tasks.matching { it.name == "pnpmInstall" }.configureEach { dependsOn(generation) }
    }

    internal fun include(report: TaskProvider<PipelineReport>) {
        graph.configure { plans.from(report.flatMap { it.outputFile }) }
    }

    companion object {
        internal fun get(project: Project): BuildWorkspace {
            val root = project.rootProject
            return root.extensions.findByType(BuildWorkspace::class.java)
                ?: BuildWorkspace(root).also { root.extensions.add("buildWorkspace", it) }
        }
    }
}
