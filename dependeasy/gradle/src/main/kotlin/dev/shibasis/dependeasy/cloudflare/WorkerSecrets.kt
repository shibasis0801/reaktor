package dev.shibasis.dependeasy.cloudflare

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.ProcessTask
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlinx.serialization.decodeFromString
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Secret names are observed from the remote Worker")
abstract class WorkerSecrets : ProcessTask() {
    @get:Input abstract val declaration: Property<String>
    @get:Input abstract val environmentName: Property<String>
    @get:Input @get:Optional abstract val requestedEnvironment: Property<String>
    @get:Input abstract val configuration: Property<String>
    @get:Input abstract val sourcePaths: ListProperty<String>
    @get:Internal abstract val rootDirectory: DirectoryProperty

    @get:Input abstract val action: Property<String>
    @get:Input @get:Optional abstract val protocolAction: Property<String>
    @get:Input @get:Optional abstract val protocolRequest: Property<String>

    @Option(option = "deploy-action", description = "Plan or apply the declared Worker secrets")
    fun deployAction(value: String) { require(value in listOf("plan", "apply")); action.set(value) }

    @get:Internal val worker: FleetWorker get() = BuildModelReader.json.decodeFromString(declaration.get())
    @get:Internal val secretsSpec: WorkerSecretsSpec get() {
        val deployment = worker.deployments.single { it.environment.name == environmentName.get() }
        return WorkerSecretsSpec(WorkerDeploySpec(worker.node.copy(name = name), deployment.environment,
            deployment.service, executionSpec(emptyList()), configuration.get()), deployment.secrets, sourcePaths.get(), deployment.retiredSecrets)
    }
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.WorkerSecrets(secretsSpec)
    @get:Internal override val kernelHost: HostRequirement get() = HostRequirement(executionPlatform.get(), ExecutionHost.Local)

    init {
        sourcePaths.convention(emptyList())
        requestedEnvironment.convention(project.providers.gradleProperty(GradleProtocol.EnvironmentOption))
        rootDirectory.convention(project.rootProject.layout.projectDirectory)
        action.convention("apply")
        protocolAction.convention(project.providers.gradleProperty(GradleProtocol.DeployActionOption))
        protocolRequest.convention(project.providers.gradleProperty(GradleProtocol.DeployRequestOption))
        executionEffect.set(Effect.Deploy)
        outputs.upToDateWhen { false }
    }

    override fun executeKernel() {
        requestedEnvironment.orNull?.let { require(it == environmentName.get()) { "Worker secrets have no environment '$it'" } }
        require(arguments.get().isEmpty()) { "Worker secret arguments belong to Wrangler" }
        val root = rootDirectory.get().asFile
        val spec = secretsSpec
        val run = UUID.randomUUID().toString()
        val execute: (ProcessExecution, String?) -> String = { command, input ->
            val output = ByteArrayOutputStream()
            val result = execOperations.exec {
                workingDir = File(command.directory); executable = command.executable; args(command.arguments)
                environment(executionEnvironment()); standardOutput = output; errorOutput = OutputStream.nullOutputStream()
                if (input != null) standardInput = ByteArrayInputStream(input.toByteArray(Charsets.UTF_8))
                isIgnoreExitValue = true
            }
            require(result.exitValue == 0) { "Worker secret tool failed; output withheld" }
            output.toString(Charsets.UTF_8)
        }
        val record: (DeployReceipt) -> Unit = { WorkerDeployKernel.record(root, it) }
        when (protocolAction.orNull ?: action.get()) {
            "plan" -> {
                val revision = ByteArrayOutputStream()
                execOperations.exec {
                    workingDir = root; executable = "git"; args("rev-parse", "HEAD")
                    standardOutput = revision; errorOutput = OutputStream.nullOutputStream()
                }.assertNormalExitValue()
                val plan = WorkerSecretsKernel.plan(spec, revision.toString().trim(),
                    java.net.InetAddress.getLocalHost().hostName, execute)
                WorkerSecretsKernel.store(root, plan)
                WorkerSecretsKernel.observe(plan, run, record)
                logger.lifecycle("Worker secrets {}: missing {}; extra {}", spec.deployment.node, plan.missing, plan.extra)
            }
            "apply" -> {
                val request = requireNotNull(protocolRequest.orNull) { "Worker secrets require a reviewed build plan" }
                val plan = BuildModelReader.readRequest(File(request)).secrets.single { it.spec.deployment.node == spec.deployment.node }
                require(plan.spec == spec) { "Worker secret declaration changed after planning" }
                WorkerSecretsKernel.apply(plan, root, run, execute, record)
            }
            else -> error("Unsupported Worker secret action")
        }
    }
}
