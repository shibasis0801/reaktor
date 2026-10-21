package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.BuildOption
import dev.shibasis.dependeasy.configuration.executionOption
import dev.shibasis.dependeasy.model.*
import kotlinx.serialization.encodeToString
import org.gradle.api.Project
import org.gradle.api.internal.BuildDefinition
import org.gradle.api.flow.*
import org.gradle.api.tasks.Input
import org.gradle.api.services.ServiceReference
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSkippedResult
import org.gradle.tooling.events.task.TaskSuccessResult
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

abstract class GradleReceiptService : BuildService<GradleReceiptService.Parameters>, OperationCompletionListener, AutoCloseable {
    interface Parameters : BuildServiceParameters {
        val root: DirectoryProperty
        val output: DirectoryProperty
        val build: Property<String>
        val includedBuilds: ListProperty<String>
        val requestedTasks: ListProperty<String>
        val privateProperties: MapProperty<String, String>
        val requestDigest: Property<String>
    }
    private val observations = ConcurrentHashMap<NodeId, TaskObservation>()
    private val run = UUID.randomUUID().toString()
    private val started = System.currentTimeMillis()
    private var buildFailure: Throwable? = null
    private var dryRun = false
    fun finish(result: BuildWorkResult, dryRun: Boolean) {
        buildFailure = result.failure.orElse(null)
        this.dryRun = dryRun
    }

    override fun onFinish(event: FinishEvent) {
        if (event !is TaskFinishEvent) return
        val path = event.descriptor.taskPath
        val first = path.trimStart(':').substringBefore(':')
        val task = if (first in parameters.includedBuilds.get() && path.count { it == ':' } > 1)
            NodeId.task(first, path.removePrefix(":$first")) else NodeId.task(parameters.build.get(), path)
        val result = event.result
        val outcome = when (result) {
            is TaskFailureResult -> TaskOutcome.Failed
            is TaskSkippedResult -> TaskOutcome.Skipped
            is TaskSuccessResult -> when {
                result.isFromCache -> TaskOutcome.FromCache
                result.isUpToDate -> TaskOutcome.UpToDate
                else -> TaskOutcome.Executed
            }
            else -> error("Unsupported Gradle task result ${result.javaClass.name}")
        }
        fun messages(failure: org.gradle.tooling.Failure, depth: Int = 0): List<String> =
            listOf(redact(failure.message.orEmpty()).take(8192)) +
                if (depth < 12) failure.causes.flatMap { messages(it, depth + 1) } else emptyList()
        val failures = (result as? TaskFailureResult)?.failures.orEmpty().flatMap { messages(it) }.take(32)
        observations[task] = TaskObservation(task, outcome, result.startTime, (result.endTime - result.startTime).coerceAtLeast(0), failures)
    }

    override fun close() {
        val root = parameters.root.get().asFile
        val loaded = runCatching { BuildModelReader.read(root) }
        val model = loaded.getOrNull()
        val requested = parameters.requestedTasks.get()
        val selected = requested.map { if (it.startsWith(':')) it else ":$it" }
            .filterNot { it.substringAfterLast(':') in setOf(GradleProtocol.ExportTask, GradleProtocol.PlanTask, GradleProtocol.RunTask) }.toSet()
        val own = parameters.build.get()
        val declared = model?.nodes.orEmpty().filter { node ->
            val invocation = node.invocation as? Invocation.Gradle
            invocation != null && invocation.tasks.isNotEmpty() && invocation.tasks.all {
                (if (node.id.build == own) it else ":${node.id.build}$it") in selected
            }
        }.map { it.id }.sortedBy { it.toString() }
        val covered = model?.nodes.orEmpty().filter { it.id in declared }.flatMap { node ->
            (node.invocation as Invocation.Gradle).tasks.map { if (node.id.build == own) it else ":${node.id.build}$it" }
        }.toSet()
        val observedRequests = observations.keys.filter { task ->
            (if (task.build == own) "" else ":${task.build}") + task.project.trimEnd(':') + ":" + task.name in (selected - covered)
        }
        val nodes = (declared + observedRequests).distinct().sortedBy { it.toString() }
        val tasks = observations.values.sortedWith(compareBy({ it.startedAtEpochMillis }, { it.task.toString() }))
        val receipt = RunReceipt(run = run, build = own, requestedNodes = nodes, requestedTasks = requested,
            startedAtEpochMillis = tasks.minOfOrNull { it.startedAtEpochMillis } ?: started, finishedAtEpochMillis = System.currentTimeMillis(),
            status = if (buildFailure != null || tasks.any { it.outcome == TaskOutcome.Failed }) ReceiptStatus.Failed else ReceiptStatus.Succeeded,
            tasks = tasks, dryRun = dryRun, modelDigest = model?.let { BuildModelReader.digest(BuildModelReader.encode(it)) },
            modelOmission = loaded.exceptionOrNull()?.let { redact(it.message.orEmpty()).take(1024) }
                ?: "Requested task identity is observed by Gradle; no exported build declaration".takeIf { observedRequests.any { it !in declared } },
            failures = generateSequence(buildFailure) { it.cause }.take(12).map { redact(it.message.orEmpty()).take(8192) }.toList(),
            requestDigest = parameters.requestDigest.orNull, operationRunId = BuildOption.environmentSnapshot()[GradleProtocol.OperationRunVariable],
            omissionCounts = model?.let(BuildModelReader::omissionCounts).orEmpty(),
            resolvedOptions = model?.let { declaredModel -> declaredModel.nodes.filter { it.id in nodes }
                .flatMap { declaredModel.buildOptions(it.id) }.distinct() }.orEmpty())
        val directory = parameters.output.get().asFile.toPath()
        Files.createDirectories(directory)
        val temporary = directory.resolve("$run.pending")
        Files.writeString(temporary, BuildModelReader.json.encodeToString(receipt) + "\n")
        Files.move(temporary, directory.resolve("$run.json"), ATOMIC_MOVE)
    }

    private fun redact(message: String): String {
        val sensitive = Regex("(?i)(token|password|secret|credential|private.?key|api.?key)")
        val values = parameters.privateProperties.get().filterKeys { sensitive.containsMatchIn(it) }.values +
            BuildOption.environmentSnapshot().filterKeys { sensitive.containsMatchIn(it) }.values +
            BuildOption.systemPropertySnapshot().entries.filter { sensitive.containsMatchIn(it.key.toString()) }.map { it.value.toString() }
        return values.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
            .fold(message) { text, value -> text.replace(value, "[REDACTED]") }
            .replace(Regex("(?i)((?:token|password|secret|credential|private.?key|api.?key)[\\w.-]*\\s*[=:]\\s*)[^\\s,;]+"), "$1[REDACTED]")
    }
}

abstract class GradleReceiptCompletion : FlowAction<GradleReceiptCompletion.Parameters> {
    interface Parameters : FlowParameters {
        @get:Input val result: Property<BuildWorkResult>
        // Gradle 9.7 caches scalar StartParameter values; the service is rebound per invocation.
        // Remove this internal bridge when Gradle exposes it publicly: gradle/gradle#39267.
        @get:Input val invocation: Property<BuildDefinition>
        @get:ServiceReference val receipt: Property<GradleReceiptService>
    }
    override fun execute(parameters: Parameters) {
        parameters.receipt.get().finish(parameters.result.get(), parameters.invocation.get().startParameter.isDryRun)
    }
}

abstract class GradleReceiptRegistration @Inject constructor(
    private val events: BuildEventsListenerRegistry,
    private val flows: FlowScope,
    private val providers: FlowProviders,
    private val invocation: BuildDefinition,
) {
    fun register(project: Project) {
        val service = project.gradle.sharedServices.registerIfAbsent("dependeasyReceipts", GradleReceiptService::class.java) {
            parameters.root.set(project.rootProject.layout.projectDirectory)
            parameters.output.set(project.layout.buildDirectory.dir(GradleProtocol.ReceiptDirectory.removePrefix("build/")))
            parameters.build.set(project.rootProject.name)
            parameters.includedBuilds.set(project.gradle.includedBuilds.map { it.name })
            parameters.requestedTasks.set(project.gradle.startParameter.taskNames)
            parameters.privateProperties.set(project.gradle.startParameter.projectProperties)
            parameters.requestDigest.set(project.executionOption(BuildOption.property(GradleProtocol.CorrelationOption)))
        }
        events.onTaskCompletion(service)
        flows.always(GradleReceiptCompletion::class.java) {
            parameters.result.set(providers.buildWorkResult)
            parameters.invocation.set(invocation)
            parameters.receipt.set(service)
        }
    }
}
