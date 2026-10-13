package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.dag.BuildPipeline
import dev.shibasis.dependeasy.dagger.DaggerModule
import dev.shibasis.dependeasy.cloudflare.WorkerFleet
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.publishing.PublicationDefaults
import kotlinx.serialization.encodeToString
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

class BuildWorkspace internal constructor(private val project: Project) {
    private val targets = linkedMapOf<String, BuildTarget>()
    val graph = project.tasks.register<ExportBuildModelTask>("exportBuildModel") {
        group = "dependeasy"
        description = "Export declared build nodes, source sites and kernels without executing producers"
        buildName.set(project.name)
        version.set(PublicationDefaults.Version)
        nodes.convention(emptyList())
        edges.convention(emptyList())
        omissions.convention(emptyList())
        taskFamilies.convention(emptyMap())
        workspaceDirectory.set(project.layout.projectDirectory)
        directories.set(mapOf(project.name to ".") + project.gradle.includedBuilds.associate {
            it.name to it.projectDir.canonicalFile.relativeTo(project.rootDir.canonicalFile).invariantSeparatorsPath
        })
        declarationFiles.from(project.fileTree(project.rootDir) {
            include("**/*.gradle.kts", "**/gradle.properties")
            exclude("**/build/**", "**/.gradle/**", "**/.github_modules/**", "**/node_modules/**", "dependeasy/**")
        }.files)
        outputFile.set(project.layout.buildDirectory.file(BuildModelReader.RelativePath.removePrefix("build/")))
        remoteHostsFile.set(project.layout.buildDirectory.file("dependeasy/remote/hosts.json"))
    }

    fun target(name: String, configure: BuildTarget.() -> Unit): TaskProvider<out Task> {
        require(name.matches(Regex("[a-z][A-Za-z0-9]*"))) { "Use a camelCase target name: $name" }
        require(name !in targets) { "Duplicate build target: $name" }
        val target = BuildTarget(project, name, project.declarationSite()).apply(configure)
        require(target.invocation.isEmpty() && target.recipes.isEmpty()) { "Declare external invocations with external()" }
        add(target)
        return project.tasks.register(name, WorkspaceTargetTask::class.java) {
            group = "dependeasy"
            dependsOn(target.dependencies)
        }
    }

    private fun add(target: BuildTarget) {
        targets[target.name] = target
        graph.configure {
            nodes.add(project.provider { BuildModelReader.encode(target.model()) })
            edges.addAll(project.provider { target.edges().map { BuildModelReader.json.encodeToString(it) } })
            omissions.addAll(project.provider { target.omissions().map { BuildModelReader.json.encodeToString(it) } })
        }
    }

    fun external(name: String, configure: BuildTarget.() -> Unit) {
        require(name !in targets) { "Duplicate build target: $name" }
        val target = BuildTarget(project, name, project.declarationSite()).apply(configure)
        require(target.invocation.isNotEmpty() || target.recipes.isNotEmpty()) { "External target '$name' needs a command or recipe" }
        add(target)
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
            includedModels.from(project.files(build.projectDir.resolve(BuildModelReader.RelativePath))
                .builtBy(build.task(":exportBuildModel")))
        }
    }

    fun packageScripts() = project.tasks.register<PackageScriptsTask>("generatePackageScripts") {
        group = "code generation"
        val file = project.layout.projectDirectory.file("package.json")
        val generated = this@BuildWorkspace.targets.filterValues { it.worker != null }.mapValues { ":${it.key}" }
        manifest.set(project.providers.fileContents(file).asText.map { authoredManifest(it, generated.keys) })
        aliases.set(generated)
        targets.set(generated.keys.associateWith { BuildModelReader.encode(this@BuildWorkspace.targets.getValue(it).model()) })
        outputFile.set(file)
    }.also { generation ->
        graph.configure { mustRunAfter(generation) }
        project.tasks.matching { it.name == "pnpmInstall" }.configureEach { dependsOn(generation) }
    }

    internal fun include(pipeline: BuildPipeline) {
        graph.configure {
            nodes.addAll(project.provider { pipeline.modelNodes().map(BuildModelReader::encode) })
            edges.addAll(project.provider { pipeline.modelEdges().map { BuildModelReader.json.encodeToString(it) } })
            omissions.addAll(project.provider { pipeline.modelOmissions().map { BuildModelReader.json.encodeToString(it) } })
        }
    }

    companion object {
        internal fun get(project: Project): BuildWorkspace {
            val root = project.rootProject
            return root.extensions.findByType(BuildWorkspace::class.java)
                ?: BuildWorkspace(root).also { root.extensions.add("buildWorkspace", it) }
        }
    }
}

internal fun Project.declareArtifactFamily(task: String, family: String) {
    val taskPath = path.trimEnd(':') + ":" + task
    BuildWorkspace.get(this).graph.configure { taskFamilies.put(taskPath, family) }
}
