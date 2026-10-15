package dev.shibasis.dependeasy.cloudflare

import dev.shibasis.dependeasy.plugins.DependeasyExtension
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.declarationSite
import kotlinx.serialization.encodeToString
import org.gradle.kotlin.dsl.register
import kotlinx.serialization.json.*
import org.gradle.api.Project
import java.io.File
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*

enum class WorkerVerification { Auth, Smoke, Behaviour, Public }

class WorkerFleet internal constructor(private val project: Project) {
    private data class Declaration(val directory: File, val prerequisites: List<NodeHandle>, val site: SourceSite?, val release: Boolean)
    private val workers = linkedMapOf<String, Declaration>()
    private val environmentOption = BuildOption.string("dependeasy.environment", environment = "TARGET_ENV")
    val prerequisites = mutableListOf<NodeHandle>()
    val environments = mutableListOf<Environment>()
    private var suiteConfig: String? = null
    private var suiteEnvironmentFiles: Map<Environment, String> = emptyMap()
    private val verification = linkedMapOf<Pair<WorkerVerification, Environment>, NodeHandle>()
    fun verification(mode: WorkerVerification, environment: Environment): NodeHandle = verification.getValue(mode to environment)
    fun karate(configDirectory: String, environmentFiles: Map<Environment, String> = emptyMap()) {
        suiteConfig = configDirectory; suiteEnvironmentFiles = environmentFiles
    }
    var release: NodeHandle? = null
        private set
    fun worker(ref: WorkerRef, vararg prerequisites: NodeHandle, release: Boolean = false) {
        require(!File(ref.directory).isAbsolute) { "Worker directories must be relative" }
        require(workers.put(ref.name, Declaration(project.file(ref.directory), prerequisites.toList(), project.declarationSite(), release)) == null) { "Duplicate worker: ${ref.name}" }
    }

    internal fun register(workspace: BuildWorkspace) {
        require(environments.isNotEmpty()) { "Declare the fleet environments" }
        val configs = workers.mapValues { Json.parseToJsonElement(it.value.directory.resolve("wrangler.json").readText()).jsonObject }
        val records = workers.map { (name, declaration) ->
            val directory = declaration.directory
            val selected = environments.filter { it == environments.last() || directory.resolve("wrangler.${it.name}.json").isFile }
            WorkerDeployable(name, NodeId(project.rootProject.name, project.path, "deploy${name.replaceFirstChar(Char::uppercaseChar)}"),
                project.rootProject.relativePath(directory),
                selected.associate { it.name to project.rootProject.relativePath(directory.resolve("wrangler.dependeasy-${it.name}.json")) },
                selected.associate { environment ->
                    val file = directory.resolve(if (environment == environments.last()) "wrangler.json" else "wrangler.${environment.name}.json")
                    environment.name to Json.parseToJsonElement(file.readText()).jsonObject.getValue("name").jsonPrimitive.content
                })
        }
        val generate = project.tasks.register<WorkerFleetTask>("generateWorkerFleet") {
            group = "code generation"
            root.set(project.rootProject.layout.projectDirectory)
            defaults.set(project.rootProject.layout.projectDirectory.file("wrangler.json"))
            declaration.set(BuildModelReader.json.encodeToString(WorkerFleetContract(workers = records)))
            configurations.from(records.flatMap { worker -> worker.configurations.keys.map { environment ->
                project.rootProject.file(worker.directory).resolve(if (environment == environments.last().name) "wrangler.json" else "wrangler.$environment.json")
            } })
            finalConfigurations.from(records.flatMap { it.configurations.values }.map(project.rootProject::file))
            contract.set(project.rootProject.layout.buildDirectory.file("dependeasy/cloudflare/fleet.json"))
        }
        val names = configs.entries.associate { it.value.getValue("name").jsonPrimitive.content to it.key }
        val extension = DependeasyExtension.get(project)
        val nodes = linkedMapOf<String, NodeHandle>()
        records.forEach { worker ->
            val name = worker.name
            val declaration = workers.getValue(name)
            val directory = declaration.directory
            val configurations = worker.configurations
            val paths = configurations.mapValues { project.rootProject.file(it.value).relativeTo(directory).invariantSeparatorsPath }
            val selected = project.providers.gradleProperty("dependeasy.environment")
                .orElse(project.providers.environmentVariable("TARGET_ENV")).orElse(environments.last().name)
                .map { value -> value.takeIf { it in configurations } ?: environments.last().name }
            val component = extension.javascript("${name}Worker", directory)
            val command = component.deploy(producers = arrayOf(generate))
            command.configure {
                configuration.set(selected.map { value -> paths.getValue(value) })
                requestedEnvironment.set(project.providers.gradleProperty("dependeasy.environment").orElse(project.providers.environmentVariable("TARGET_ENV")))
                allowedEnvironments.set(configurations.keys.toList())
                sourceFiles.from(generate.map { it.finalConfigurations })
                arguments.add("--minify")
            }
            val logs = component.command("logs", null, "exec", "wrangler", "tail")
            logs.configure {
                arguments.addAll(selected.map { value -> listOf("--config", paths.getValue(value)) })
                dependsOn(generate)
                outputs.upToDateWhen { false }
            }
            val bindings = configs.getValue(name)["services"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("service").jsonPrimitive.content }
            val dependencies = listOf(project.node(command)) + prerequisites + declaration.prerequisites
            val spec = KernelSpec.WorkspaceNode(worker.node, NodeKind.Deploy, Run.onLinux().onWorker(),
                dependencies.map { it.id }, emptyList(), emptyList(), emptyList(),
                worker = WorkerRef(name, project.relativePath(directory)),
                environments = this@WorkerFleet.environments.filter { it.name in configurations }, bindings = bindings.map(::ServiceRef))
            nodes[name] = workspace.emit(project, spec, WorkspaceCompletion, declaration.site, null, dependencies, emptyList(), emptyList())
            if (declaration.release) {
                require(release == null) { "Declare one release web target" }
                release = nodes.getValue(name)
            }
            val deploy = nodes.getValue(name)
            val tail = project.node(logs)
            workspace.alias(directory, "deploy", deploy)
            workspace.alias(directory, "logs", tail)
            workspace.alias(project.rootDir, "logs${name.replaceFirstChar(Char::uppercaseChar)}", tail)
            worker.configurations.keys.forEach { environment ->
                val suffix = if (environment == environments.last().name) "" else environment.replaceFirstChar(Char::uppercaseChar)
                val settings = listOf(environmentOption.argument(environment))
                workspace.alias(project.rootDir, "deploy${name.replaceFirstChar(Char::uppercaseChar)}$suffix", deploy, settings = settings)
                workspace.alias(project.rootDir, "logs${name.replaceFirstChar(Char::uppercaseChar)}$suffix", tail, settings = settings)
            }
        }
        suiteConfig?.let { config ->
            val groups = linkedMapOf<String, MutableList<NodeHandle>>()
            records.forEach { worker ->
                val target = project.file(worker.directory).name
                val directory = "tests/$target/karate"
                if (!project.file(directory).isDirectory) return@forEach
                val modes = linkedMapOf("auth" to emptyList<String>(), "smoke" to listOf("@smoke"))
                if (project.file("$directory/behaviour.feature").isFile) modes["behaviour"] = listOf("@behaviour")
                if (project.file("$directory/public.feature").isFile) modes["public"] = emptyList()
                worker.configurations.keys.forEach { environment ->
                    modes.forEach { (mode, tags) ->
                        val name = "karate" + target.replaceFirstChar(Char::uppercaseChar) + mode.replaceFirstChar(Char::uppercaseChar) + environment.replaceFirstChar(Char::uppercaseChar)
                        val suite = workspace.karateSuite(name, if (mode == "public") "$directory/public.feature" else directory, config, tags,
                            listOfNotNull(suiteEnvironmentFiles[Environment(environment)]))
                        val suffix = if (environment == environments.last().name) "" else ":$environment"
                        val alias = if (mode == "smoke") "karate:$target:auth:smoke" else "karate:$target:$mode"
                        workspace.alias(project.rootDir, alias + suffix, suite)
                        if (mode == "auth") workspace.alias(project.rootDir, "test:$target:karate$suffix", suite)
                        groups.getOrPut("$mode:$environment") { mutableListOf() }.add(suite)
                    }
                }
            }
            groups.forEach { (selection, suites) ->
                val (mode, environment) = selection.split(':')
                val name = "workerKarate" + mode.replaceFirstChar(Char::uppercaseChar) + environment.replaceFirstChar(Char::uppercaseChar)
                val suite = workspace.aggregate(project, name, NodeKind.Verification, suites)
                verification[WorkerVerification.entries.single { it.name.equals(mode, true) } to Environment(environment)] = suite
                val suffix = (if (mode == "auth") "" else ":$mode") + if (environment == environments.last().name) "" else ":$environment"
                workspace.alias(project.rootDir, "test:workers:karate$suffix", suite)
            }
        }
        workers.keys.forEach { name ->
            val bindings = configs.getValue(name)["services"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("service").jsonPrimitive.content }
            workspace.configure(nodes.getValue(name)) {
                includes(*bindings.mapNotNull(names::get).filter { it != name }.map(nodes::getValue).toTypedArray())
            }
        }
        workspace.aggregate(project, "deployWorkers", NodeKind.Deploy, nodes.values.toList(), Run.onLinux().onWorker())
    }
}
