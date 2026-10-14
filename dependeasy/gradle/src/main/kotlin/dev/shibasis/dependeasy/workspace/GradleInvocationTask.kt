package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.files.deleteTreeSafely
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.task.*
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.UUID

@DisableCachingByDefault(because = "Each invocation observes Gradle on the selected execution host")
abstract class GradleInvocationTask : DefaultTask() {
    @get:Internal abstract val rootDirectory: DirectoryProperty
    @get:Internal abstract val gradleHome: DirectoryProperty
    @get:Internal abstract val gradleUserHome: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val requestFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val modelFile: RegularFileProperty
    @get:Input abstract val planning: Property<Boolean>
    @get:Input abstract val maxWorkers: Property<Int>
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val initScripts: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction fun invoke() {
        val root = rootDirectory.get().asFile.canonicalFile
        val owned = root.resolve(GradleProtocol.RequestDirectory).toPath()
        require(owned.startsWith(root.toPath()) && (root.toPath().nameCount..owned.nameCount).none { count ->
            java.nio.file.Files.isSymbolicLink(owned.root.resolve(owned.subpath(0, count)))
        }) { "Gradle request directory cannot contain symbolic links" }
        val file = requestFile.get().asFile.canonicalFile
        require(file.toPath().startsWith(owned) && !java.nio.file.Files.isSymbolicLink(requestFile.get().asFile.toPath())) {
            "Gradle requests must be inside the owned request directory"
        }
        val request = BuildModelReader.readRequest(file)
        val model = BuildModelReader.read(root)
        val digest = BuildModelReader.digest(BuildModelReader.encode(model))
        require(request.modelDigest == digest) { "Build model changed; review a fresh plan" }
        val node = model.nodes.single { it.id == request.node }
        require(request.declaredAt == node.declaredAt) { "Build declaration changed; review a fresh plan" }
        val invocation = node.invocation as? Invocation.Gradle ?: error("This node has no Gradle invocation")
        val own = model.builds.single { it.directory == "." }.name
        val tasks = invocation.tasks.map { if (node.id.build == own) it else ":${node.id.build}$it" }
        require(tasks.none { it.substringAfterLast(':') in setOf(GradleProtocol.PlanTask, GradleProtocol.RunTask) }) {
            "Build planning and execution bridges cannot invoke themselves"
        }
        require(request.arguments.none { argument ->
            argument in setOf("-p", "--project-dir", "-g", "--gradle-user-home") ||
                argument.startsWith("-p") || argument.startsWith("-g") ||
                argument.startsWith("--project-dir=") || argument.startsWith("--gradle-user-home=") ||
                argument.startsWith("-Pdependeasy.")
        }) { "Invocation arguments cannot change workspace ownership or the Gradle protocol" }
        val requestDigest = BuildModelReader.digest(file)
        require(file.name == "$requestDigest.json") { "Gradle request content changed; review a fresh plan" }
        val inherited = initScripts.files.flatMap { listOf("--init-script", it.absolutePath) }
        val connection = GradleConnector.newConnector().forProjectDirectory(root)
            .useInstallation(gradleHome.get().asFile).useGradleUserHomeDir(gradleUserHome.get().asFile).connect()
        connection.use {
            val build = it.newBuild().forTasks(*tasks.toTypedArray())
                .setJavaHome(File(System.getProperty("java.home")))
                .setEnvironmentVariables(System.getenv())
                .setStandardOutput(System.out).setStandardError(System.err)
            val workerArgument = if (request.arguments.none { it.startsWith("--max-workers") })
                listOf("--max-workers=${maxWorkers.get()}") else emptyList()
            val context = buildJsonObject {
                put("java", System.getProperty("java.home"))
                put("gradle", gradleHome.get().asFile.absolutePath)
                val userProperties = gradleUserHome.get().asFile.resolve("gradle.properties")
                put("userProperties", if (userProperties.isFile) BuildModelReader.digest(userProperties) else "absent")
                put("initScripts", BuildModelReader.digest(initScripts.files.sortedBy { it.absolutePath }.joinToString("\n") { BuildModelReader.digest(it) }))
                put("environment", BuildModelReader.digest(BuildModelReader.json.encodeToString<Map<String, String>>(System.getenv().filterKeys { key -> key !in setOf("REMOTE_DEV_RUN_ID", "DEPENDEASY_OPERATION_RUN_ID", "_") && !key.matches(Regex("APP_ICON_[0-9]+")) }.toSortedMap())))
                put("systemProperties", BuildModelReader.digest(System.getProperties().entries
                    .filter { entry -> entry.key.toString().startsWith("org.gradle.project.") }
                    .sortedBy { entry -> entry.key.toString() }.joinToString("\n") { entry -> "${entry.key}=${entry.value}" }))
            }
            val contextDigest = BuildModelReader.digest(context.toString())
            if (planning.get()) {
                val directory = outputDirectory.get().asFile.apply { mkdirs() }
                val cache = directory.resolve("cache/${BuildModelReader.digest(requestDigest + contextDigest)}.json")
                val output = directory.resolve("$requestDigest.json")
                if (cache.isFile) {
                    val cached = BuildModelReader.readPlan(cache)
                    require(cached.modelDigest == digest && cached.requestDigest == requestDigest && cached.contextDigest == contextDigest)
                    output.writeText(cache.readText())
                    return
                }
                val scratch = root.resolve("tmp/dependeasy/plans/${UUID.randomUUID()}").apply { mkdirs() }
                try {
                    val selected = scratch.resolve("selected").apply { mkdirs() }
                    val script = scratch.resolve("selected.gradle")
                    script.writeText(selectedGraphScript(model, root, selected))
                    val preparation = java.util.concurrent.ConcurrentHashMap.newKeySet<NodeId>()
                    build.addProgressListener({ event ->
                        if (event is TaskFinishEvent) {
                            val path = event.descriptor.taskPath
                            val first = path.trimStart(':').substringBefore(':')
                            val owner = model.builds.firstOrNull { identity -> identity.name == first && path.count { char -> char == ':' } > 1 }?.name
                            preparation.add(NodeId.task(owner ?: own, if (owner == null) path else path.removePrefix(":$owner")))
                        }
                    }, OperationType.TASK)
                    val started = System.nanoTime()
                    build.withArguments(inherited + request.arguments + workerArgument + listOf("--dry-run", "--no-configuration-cache",
                        "--init-script", script.absolutePath)).run()
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    val observed = selected.listFiles().orEmpty().filter { selectedFile -> selectedFile.extension == "json" }
                        .sortedBy { selectedFile -> selectedFile.name }.flatMap { selectedFile ->
                            BuildModelReader.json.decodeFromString<List<PlannedGradleTask>>(selectedFile.readText())
                        }.groupBy { task -> task.id }.map { (id, copies) ->
                            require(copies.distinct().size == 1) { "Conflicting selected task $id" }
                            copies.first()
                        }.sortedBy { task -> task.id.toString() }
                    require(observed.isNotEmpty()) { "Gradle returned no selected tasks" }
                    val requested = observed.filterNot { task -> task.id in preparation }
                    val selectedIds = requested.map { task -> task.id }.toSet()
                    val planned = requested.map { task -> task.copy(
                        dependencies = task.dependencies.filter(selectedIds::contains),
                        dependenciesOutsideSelection = task.dependencies.filterNot(selectedIds::contains)) }
                    val plan = GradlePlan(node = node.id, modelDigest = digest, requestDigest = requestDigest,
                        contextDigest = contextDigest, invocation = tasks, configuredMillis = elapsed, tasks = planned,
                        preparationTasks = preparation.sortedBy { task -> task.toString() })
                    val text = BuildModelReader.json.encodeToString(plan) + "\n"
                    cache.parentFile.mkdirs(); cache.writeText(text); output.writeText(text)
                    BuildModelReader.readPlan(output)
                } finally { scratch.deleteTreeSafely(within = root.resolve("tmp")) }
            } else {
                require(request.expectedContextDigest == contextDigest) { "Gradle execution host or configuration changed; review a fresh plan" }
                val before = root.resolve(GradleProtocol.ReceiptDirectory).listFiles().orEmpty().map { it.name }.toSet()
                build.addProgressListener({ event ->
                    when (event) {
                        is TaskStartEvent -> progress(GradleTaskProgress(task = event.descriptor.taskPath))
                        is TaskFinishEvent -> {
                            val outcome = when (val result = event.result) {
                                is TaskFailureResult -> TaskOutcome.Failed
                                is TaskSkippedResult -> TaskOutcome.Skipped
                                is TaskSuccessResult -> when {
                                    result.isFromCache -> TaskOutcome.FromCache
                                    result.isUpToDate -> TaskOutcome.UpToDate
                                    else -> TaskOutcome.Executed
                                }
                                else -> error("Unsupported Gradle task result")
                            }
                            progress(GradleTaskProgress(task = event.descriptor.taskPath, outcome = outcome))
                        }
                    }
                }, OperationType.TASK)
                try {
                    build.withArguments(inherited + request.arguments + workerArgument + "-P${GradleProtocol.CorrelationOption}=$requestDigest").run()
                } finally {
                    val receipts = root.resolve(GradleProtocol.ReceiptDirectory).listFiles().orEmpty()
                        .filter { receiptFile -> receiptFile.extension == "json" && receiptFile.name !in before }
                        .filter { receiptFile -> BuildModelReader.readReceipt(receiptFile).requestDigest == requestDigest }
                        .sortedBy { receiptFile -> receiptFile.name }.map { receiptFile -> receiptFile.relativeTo(root).invariantSeparatorsPath }
                    val result = GradleRunResult(requestDigest = requestDigest, receipts = receipts, operationRunId = System.getenv(GradleProtocol.OperationRunVariable))
                    outputDirectory.get().asFile.resolve("$requestDigest.json").apply {
                        parentFile.mkdirs(); writeText(BuildModelReader.json.encodeToString(result) + "\n")
                    }
                }
            }
        }
    }

    private fun progress(value: GradleTaskProgress) = println(GradleProtocol.ProgressPrefix + BuildModelReader.json.encodeToString(value))

    private fun selectedGraphScript(model: BuildModel, root: File, output: File): String {
        val owners = model.builds.associate { root.resolve(it.directory).canonicalFile.absolutePath to it.name }
        fun literal(value: String) = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
        return """
            import groovy.json.JsonOutput
            import groovy.json.JsonSlurper
            def owners = new JsonSlurper().parseText(${literal(BuildModelReader.json.encodeToString(owners))})
            def destination = new File(${literal(output.absolutePath)})
            gradle.taskGraph.whenReady { graph ->
                if (!gradle.startParameter.dryRun || !owners.containsKey(gradle.rootProject.projectDir.canonicalPath)) return
                def identity = { task ->
                    def build = owners[task.project.rootProject.projectDir.canonicalPath]
                    if (build == null) throw new GradleException('Selected task has an undeclared build owner')
                    [build: build, project: task.project.path, name: task.name]
                }
                def selected = graph.allTasks.collect { task ->
                    [id: identity(task), dependencies: graph.getDependencies(task).collect(identity).sort { it.build + it.project + ':' + it.name }]
                }
                def owner = owners[gradle.rootProject.projectDir.canonicalPath]
                new File(destination, owner + '.json').text = JsonOutput.toJson(selected)
            }
        """.trimIndent()
    }
}
