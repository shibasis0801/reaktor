package dev.shibasis.dependeasy.cloudflare

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlinx.serialization.encodeToString
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "D1 migration plans observe the remote ledger")
abstract class D1Migration : ProcessTask() {
    @get:Input abstract val declaration: Property<String>
    @get:Input abstract val environmentName: Property<String>
    @get:Input abstract val environments: ListProperty<String>
    @get:Input @get:Optional abstract val requestedEnvironment: Property<String>
    @get:Input abstract val configuration: Property<String>
    @get:Input abstract val binding: Property<String>
    @get:Internal abstract val rootDirectory: DirectoryProperty
    @get:Input abstract val action: Property<String>
    @get:Input @get:Optional abstract val protocolAction: Property<String>
    @get:Input @get:Optional abstract val protocolRequest: Property<String>

    @get:Internal val spec: MigrationSpec get() {
        val worker = BuildModelReader.json.decodeFromString<FleetWorker>(declaration.get())
        val deployment = worker.deployments.single { it.environment.name == environmentName.get() }
        val node = worker.node.copy(name = name)
        return MigrationSpec(node, deployment.environment, DatabaseMigrations.D1("${worker.ref.directory}/migrations",
            WorkerDeploySpec(node, deployment.environment, deployment.service, executionSpec(emptyList()), configuration.get()),
            binding.get(), environments.get().map(::Environment)))
    }
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.DatabaseMigration(spec, ToolRef("pnpm", ToolchainVersions.Pnpm))
    @get:Internal override val kernelHost: HostRequirement get() = HostRequirement(executionPlatform.get(), ExecutionHost.Local)

    @Option(option = "deploy-action", description = "Plan or apply the declared D1 migrations")
    fun deployAction(value: String) { require(value in listOf("plan", "apply")); action.set(value) }

    init {
        requestedEnvironment.convention(project.providers.gradleProperty(GradleProtocol.EnvironmentOption))
        rootDirectory.convention(project.rootProject.layout.projectDirectory)
        action.convention("apply")
        protocolAction.convention(project.providers.gradleProperty(GradleProtocol.DeployActionOption))
        protocolRequest.convention(project.providers.gradleProperty(GradleProtocol.DeployRequestOption))
        executionEffect.set(Effect.Deploy)
        outputs.upToDateWhen { false }
    }

    override fun executeKernel() {
        requestedEnvironment.orNull?.let { require(it == environmentName.get()) { "D1 migrations have no environment '$it'" } }
        require(arguments.get().isEmpty()) { "D1 migration arguments belong to Wrangler" }
        val root = rootDirectory.get().asFile
        val spec = spec
        val run = UUID.randomUUID().toString()
        val execute: (ProcessExecution) -> String = { command ->
            val output = ByteArrayOutputStream()
            val result = execOperations.exec {
                workingDir = File(command.directory); executable = command.executable; args(command.arguments)
                environment(executionEnvironment()); standardOutput = output; errorOutput = OutputStream.nullOutputStream()
                isIgnoreExitValue = true
            }
            require(result.exitValue == 0) { "D1 migration tool failed; output withheld" }
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
                val plan = D1MigrationKernel.plan(spec, root, revision.toString().trim(), java.net.InetAddress.getLocalHost().hostName, execute)
                MigrationKernel.store(root, plan)
                MigrationKernel.observe(plan, run, record)
                logger.lifecycle("D1 migrations {}: pending {}", spec.node, plan.pending)
            }
            "apply" -> {
                val request = requireNotNull(protocolRequest.orNull) { "D1 migrations require a reviewed build plan" }
                val plan = BuildModelReader.readRequest(File(request)).migrations.single { it.spec.node == spec.node }
                require(plan.spec == spec) { "D1 migration declaration changed after planning" }
                D1MigrationKernel.apply(plan, root, run, execute, record)
            }
            else -> error("Unsupported D1 migration action")
        }
    }
}
