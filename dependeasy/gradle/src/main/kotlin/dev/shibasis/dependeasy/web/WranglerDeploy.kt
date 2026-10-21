package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Deployment observes and changes a remote service")
abstract class WranglerDeploy : ProcessTask() {
    @get:Input abstract val configuration: Property<String>
    @get:Input @get:Optional abstract val deploymentEnvironment: Property<String>
    @get:Input @get:Optional abstract val requestedEnvironment: Property<String>
    @get:Input abstract val allowedEnvironments: ListProperty<String>
    @get:Input abstract val declaration: Property<String>
    @get:Input abstract val environmentName: Property<String>
    @get:Input abstract val bindings: ListProperty<String>
    @get:Internal abstract val rootDirectory: DirectoryProperty
    @get:Input abstract val action: Property<String>
    @get:Input @get:Optional abstract val protocolAction: Property<String>
    @get:Input @get:Optional abstract val protocolRequest: Property<String>

    @Option(option = "deploy-action", description = "plan, apply or rollback the declared Worker")
    fun deployAction(value: String) { require(value in listOf("plan", "apply", "rollback")); action.set(value) }

    @get:Internal val worker: FleetWorker get() = BuildModelReader.json.decodeFromString(declaration.get())
    @get:Internal val deployment: WorkerDeployment get() = worker.deployments.single { it.environment.name == environmentName.get() }
    @get:Internal val deploySpec: WorkerDeploySpec get() = WorkerDeploySpec(worker.node, deployment.environment,
        deployment.service, executionSpec(emptyList()), configuration.get(), deploymentEnvironment.orNull, deployment.health)
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.WranglerDeploy(deploySpec)
    @get:Internal override val kernelHost: HostRequirement get() = HostRequirement(executionPlatform.get(), ExecutionHost.Local)

    init {
        allowedEnvironments.convention(emptyList())
        rootDirectory.convention(project.rootProject.layout.projectDirectory)
        action.convention("apply")
        protocolAction.convention(project.providers.gradleProperty(GradleProtocol.DeployActionOption))
        protocolRequest.convention(project.providers.gradleProperty(GradleProtocol.DeployRequestOption))
        executionEffect.set(Effect.Deploy)
        outputs.upToDateWhen { false }
    }

    override fun executeKernel() {
        requestedEnvironment.orNull?.let { require(it in allowedEnvironments.get()) { "Worker deployment has no environment '$it'" } }
        require(arguments.get().isEmpty()) { "Worker deploy arguments belong to Wrangler" }
        val spec = deploySpec
        val root = rootDirectory.get().asFile
        val run = UUID.randomUUID().toString()
        val execute: (ProcessExecution) -> String = { command ->
            val output = ByteArrayOutputStream()
            execOperations.exec {
                workingDir = File(command.directory); executable = command.executable; args(command.arguments)
                environment(executionEnvironment()); standardOutput = output; errorOutput = ByteArrayOutputStream()
            }.assertNormalExitValue()
            output.toString(Charsets.UTF_8)
        }
        val record: (DeployReceipt) -> Unit = { WorkerDeployKernel.record(root, it) }
        val requestedAction = protocolAction.orNull ?: action.get()
        when (requestedAction) {
            "plan" -> {
                val revision = ByteArrayOutputStream()
                execOperations.exec { workingDir = root; executable = "git"; args("rev-parse", "HEAD"); standardOutput = revision }.assertNormalExitValue()
                val plan = WorkerDeployKernel.plan(spec, root.resolve("${GradleProtocol.PlanDirectory}/deploy/bundles/$run"),
                    revision.toString().trim(), java.net.InetAddress.getLocalHost().hostName, execute)
                WorkerDeployKernel.store(root, plan)
                WorkerDeployKernel.observe(plan, run, record)
                logger.lifecycle("Worker plan {}: {} differences; unobserved settings {}", spec.node, plan.differences.size, plan.unobservedSettings)
            }
            "apply", "rollback" -> {
                val plan = protocolRequest.orNull?.let { request ->
                    BuildModelReader.readRequest(File(request)).deployments.single { it.spec.node == spec.node }
                } ?: WorkerDeployKernel.retained(root, spec.node, spec.environment)
                require(plan.spec == spec) { "Deployment declaration changed after planning" }
                if (requestedAction == "rollback") {
                    val receipts = WorkerDeployKernel.receipts(root, spec.node).filter { it.environment == spec.environment && it.result == ReceiptStatus.Succeeded }
                    val latest = receipts.first { it.remoteVersion == plan.previousVersion && it.action == "apply" }
                    val previous = receipts.first { it.remoteVersion == latest.rollbackTarget }
                    WorkerDeployKernel.rollback(plan, latest, previous, run, execute, record)
                } else WorkerDeployKernel.apply(plan, run, execute, record)
            }
            else -> error("Unsupported deployment action")
        }
    }
}
