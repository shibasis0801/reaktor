package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.files.deleteTreeSafely
import kotlinx.serialization.encodeToString
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.task.*
import java.io.File
import java.util.UUID

/** Gradle and Tooling API binding; request and plan decisions live in the plain Kotlin kernel. */
object GradleToolingRunner {
    fun invoke(root: File, gradleHome: File, gradleUserHome: File, javaHome: String, maxWorkers: Int,
        initScripts: Set<File>, environment: Map<String, String>, outputDirectory: File, planning: Boolean,
        model: BuildModel, invocation: ValidatedGradleInvocation, contextDigest: String) {
        val request = invocation.request
        val requestDigest = invocation.requestDigest
        val tasks = invocation.tasks
        val own = invocation.own
        val inherited = initScripts.flatMap { listOf("--init-script", it.absolutePath) }
        val connection = GradleConnector.newConnector().forProjectDirectory(root)
            .useInstallation(gradleHome).useGradleUserHomeDir(gradleUserHome).connect()
        connection.use {
            val build = it.newBuild()
                .setJavaHome(File(javaHome))
                .setEnvironmentVariables(environment)
                .setStandardOutput(System.out).setStandardError(System.err)
            val workerArgument = if (request.arguments.none { it.startsWith("--max-workers") })
                listOf("--max-workers=${maxWorkers}") else emptyList()
            if (planning) {
                val directory = outputDirectory.apply { mkdirs() }
                val scratch = root.resolve("tmp/dependeasy/plans/${UUID.randomUUID()}").apply { mkdirs() }
                try {
                    val selected = scratch.resolve("selected").apply { mkdirs() }
                    val script = scratch.resolve("selected.gradle")
                    val owners = scratch.resolve("owners.json").apply {
                        writeText(BuildModelReader.json.encodeToString(model.builds.associate {
                            root.resolve(it.directory).canonicalFile.absolutePath to it.name
                        }))
                    }
                    script.writeText(selectedGraphScript(owners, selected))
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
                    build.withArguments(inherited + workerArgument + tasks + request.arguments + listOf("--dry-run", "--no-configuration-cache",
                        "--init-script", script.absolutePath)).run()
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    val preparationTasks = preparation.toSet()
                    val observed = selected.listFiles().orEmpty().filter { selectedFile -> selectedFile.extension == "json" }
                        .sortedBy { selectedFile -> selectedFile.name }.flatMap { selectedFile ->
                            BuildModelReader.json.decodeFromString<List<PlannedGradleTask>>(selectedFile.readText())
                        }.groupBy { task -> task.id }.map { (id, copies) ->
                            require(copies.distinct().size == 1) { "Conflicting selected task $id" }
                            copies.first()
                        }.sortedBy { task -> task.id.toString() }
                    val deployments = GradleInvocationKernel.deploymentNodes(model, invocation.node.id).filter { node -> observed.any { task -> task.id == node.id } }
                    if (deployments.isNotEmpty()) {
                        val deployTasks = deployments.flatMap { node -> (node.invocation as Invocation.Gradle).tasks.map { task ->
                            if (node.id.build == own) task else ":${node.id.build}$task"
                        } }
                        val arguments = request.arguments.toMutableList()
                        val action = arguments.indexOf("--deploy-action")
                        if (action >= 0) { require(action + 1 < arguments.size); arguments.removeAt(action + 1); arguments.removeAt(action) }
                        arguments.removeAll { argument -> argument.startsWith("--deploy-action=") }
                        build.withArguments(inherited + workerArgument + deployTasks + arguments + "-P${GradleProtocol.DeployActionOption}=plan").run()
                    }
                    val plans = deployments.map { node ->
                        val spec = (node.kernel!!.spec as KernelSpec.WranglerDeploy).deployment
                        val owner = root.resolve(model.builds.single { identity -> identity.name == node.id.build }.directory)
                        WorkerDeployKernel.retained(owner, node.id, spec.environment)
                    }
                    GradlePlanCache.store(directory, GradleInvocationKernel.plan(invocation, contextDigest, elapsed, observed, preparationTasks).copy(deployments = plans))
                } finally { scratch.deleteTreeSafely(within = root.resolve("tmp")) }
            } else {
                GradleInvocationKernel.requireExecution(invocation, contextDigest)
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
                    val deployRequest = root.resolve("${GradleProtocol.RequestDirectory}/$requestDigest.json")
                    build.withArguments(inherited + workerArgument + tasks + request.arguments + listOf(
                        "-P${GradleProtocol.CorrelationOption}=$requestDigest", "-P${GradleProtocol.DeployRequestOption}=${deployRequest.absolutePath}")).run()
                } finally {
                    val receipts = root.resolve(GradleProtocol.ReceiptDirectory).listFiles().orEmpty()
                        .filter { receiptFile -> receiptFile.extension == "json" && receiptFile.name !in before }
                        .filter { receiptFile -> BuildModelReader.readReceipt(receiptFile).requestDigest == requestDigest }
                        .sortedBy { receiptFile -> receiptFile.name }.map { receiptFile -> receiptFile.relativeTo(root).invariantSeparatorsPath }
                    val result = GradleRunResult(requestDigest = requestDigest, receipts = receipts, operationRunId = environment[GradleProtocol.OperationRunVariable])
                    outputDirectory.resolve("$requestDigest.json").apply {
                        parentFile.mkdirs(); writeText(BuildModelReader.json.encodeToString(result) + "\n")
                    }
                }
            }
        }
    }

    private fun progress(value: GradleTaskProgress) = println(GradleProtocol.ProgressPrefix + BuildModelReader.json.encodeToString(value))

    private fun selectedGraphScript(owners: File, output: File): String {
        fun literal(value: String) = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
        val classpath = listOf(SelectedGradleGraph::class.java, BuildModelReader::class.java, BuildOption::class.java,
            kotlinx.serialization.json.Json::class.java, kotlinx.serialization.Serializable::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }.distinct()
        return """
            initscript { dependencies { classpath files(${classpath.joinToString(",", transform = ::literal)}) } }
            dev.shibasis.dependeasy.workspace.SelectedGradleGraph.install(gradle, new File(${literal(owners.absolutePath)}), new File(${literal(output.absolutePath)}))
        """.trimIndent()
    }
}
