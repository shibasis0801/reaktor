package dev.shibasis.dependeasy.server

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.process.ProcessTask
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Image content lives in the declared execution host's Docker engine")
abstract class BuildServerImage : DefaultTask(), KernelSpecOwner {
    @get:Input abstract val declaration: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val archive: RegularFileProperty
    @get:OutputFile abstract val descriptor: RegularFileProperty
    @get:Input @get:Optional abstract val protocolAction: Property<String>
    @get:Input @get:Optional abstract val protocolRequest: Property<String>
    @get:Internal val imageSpec: ServerImageSpec get() = BuildModelReader.json.decodeFromString<ServerImageSpec>(declaration.get())
        .copy(jar = archive.get().asFile.absolutePath)
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.ServerImageBuild(imageSpec)
    @get:Internal override val kernelHost get() = HostRequirement(HostPlatform.Portable, ExecutionHost.Worker)

    init {
        protocolAction.convention(project.providers.gradleProperty(GradleProtocol.DeployActionOption))
        protocolRequest.convention(project.providers.gradleProperty(GradleProtocol.DeployRequestOption))
        onlyIf { imagePreparationRequired(protocolAction.orNull, protocolRequest.orNull) }
    }

    @TaskAction fun build() {
        val image = ServerImageKernel.build(imageSpec)
        descriptor.get().asFile.apply { parentFile.mkdirs(); writeText(BuildModelReader.json.encodeToString(image) + "\n") }
    }
}

@DisableCachingByDefault(because = "Pushing publishes an immutable registry image")
abstract class PushServerImage : DefaultTask(), KernelSpecOwner {
    @get:Input abstract val declaration: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val archive: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val built: RegularFileProperty
    @get:OutputFile abstract val descriptor: RegularFileProperty
    @get:Input @get:Optional abstract val protocolAction: Property<String>
    @get:Internal val imageSpec: ServerImageSpec get() = BuildModelReader.json.decodeFromString<ServerImageSpec>(declaration.get())
        .copy(jar = archive.get().asFile.absolutePath)
    @get:Input @get:Optional abstract val protocolRequest: Property<String>
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.ServerImagePush(imageSpec, built.get().asFile.absolutePath)
    @get:Internal override val kernelEffect get() = Effect.Deploy
    @get:Internal override val kernelHost get() = HostRequirement(HostPlatform.Portable, ExecutionHost.Worker)

    init {
        protocolAction.convention(project.providers.gradleProperty(GradleProtocol.DeployActionOption))
        protocolRequest.convention(project.providers.gradleProperty(GradleProtocol.DeployRequestOption))
        onlyIf { imagePreparationRequired(protocolAction.orNull, protocolRequest.orNull) }
    }

    @TaskAction fun push() {
        val image = ServerImageKernel.push(imageSpec, BuildModelReader.json.decodeFromString(built.get().asFile.readText()))
        descriptor.get().asFile.apply { parentFile.mkdirs(); writeText(BuildModelReader.json.encodeToString(image) + "\n") }
    }
}

private fun imagePreparationRequired(action: String?, request: String?): Boolean = action != "plan" &&
    BuildModelReader.readRequest(File(requireNotNull(request) {
        "Server image preparation requires a canonical build run request"
    })).serverDeployments.isEmpty()

@DisableCachingByDefault(because = "Rollout observes and changes a remote deployment")
abstract class RolloutServer : ProcessTask() {
    @get:Input abstract val declaration: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val archive: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val published: RegularFileProperty
    @get:OutputFile abstract val generatedManifest: RegularFileProperty
    @get:Internal abstract val rootDirectory: DirectoryProperty
    @get:Input abstract val action: Property<String>
    @get:Input @get:Optional abstract val protocolAction: Property<String>
    @get:Input @get:Optional abstract val protocolRequest: Property<String>
    @get:Internal val deploySpec: ServerRolloutSpec get() = BuildModelReader.json.decodeFromString<ServerRolloutSpec>(declaration.get())
        .copy(execution = executionSpec(emptyList()))
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.ServerRollout(deploySpec, published.get().asFile.absolutePath)

    @Option(option = "deploy-action", description = "plan, apply or rollback the declared server")
    fun deployAction(value: String) { require(value in listOf("plan", "apply", "rollback")); action.set(value) }

    init {
        action.convention("plan")
        rootDirectory.convention(project.rootProject.layout.projectDirectory)
        protocolAction.convention(project.providers.gradleProperty(GradleProtocol.DeployActionOption))
        protocolRequest.convention(project.providers.gradleProperty(GradleProtocol.DeployRequestOption))
        executionEffect.set(Effect.Deploy)
        outputs.upToDateWhen { false }
    }

    override fun executeKernel() {
        val spec = deploySpec
        val root = rootDirectory.get().asFile
        val image = BuildModelReader.json.decodeFromString<PublishedServerImage>(published.get().asFile.readText())
        spec.digest(image.image)
        require(BuildModelReader.digest(archive.get().asFile) == image.jarDigest) { "Published image belongs to a different server archive" }
        val run = UUID.randomUUID().toString()
        val record: (DeployReceipt) -> Unit = { WorkerDeployKernel.record(root, it) }
        when (val requested = protocolAction.orNull ?: action.get()) {
            "plan" -> {
                val revision = ByteArrayOutputStream()
                execOperations.exec { workingDir = root; executable = "git"; args("rev-parse", "HEAD"); standardOutput = revision }.assertNormalExitValue()
                val requestedArguments = protocolRequest.orNull?.let { BuildModelReader.readRequest(File(it)).arguments }.orEmpty()
                val rollback = action.get() == "rollback" || "--deploy-action=rollback" in requestedArguments ||
                    requestedArguments.windowed(2).any { it == listOf("--deploy-action", "rollback") }
                val wanted = if (rollback) {
                    val receipts = WorkerDeployKernel.receipts(root, spec.node).filter {
                        it.environment == spec.environment && it.result == ReceiptStatus.Succeeded
                    }
                    val latest = receipts.first { it.remoteVersion != null && it.action in listOf("apply", "rollback") }
                    requireNotNull(receipts.first { it.remoteVersion == latest.rollbackTarget }.remoteVersion)
                } else image.image
                val plan = ServerRolloutKernel.plan(spec, wanted, revision.toString().trim(), java.net.InetAddress.getLocalHost().hostName)
                ServerRolloutKernel.store(root, plan)
                ServerRolloutKernel.observe(plan, run, record)
                generatedManifest.get().asFile.apply {
                    parentFile.mkdirs(); writeText(ServerRolloutKernel.manifest(spec, wanted).toString() + "\n")
                }
                logger.lifecycle("Server plan {}: {} -> {}", spec.node, plan.previousDigest, plan.image)
            }
            "apply", "rollback" -> {
                val request = requireNotNull(protocolRequest.orNull) { "Server rollout needs the canonical sealed Gradle request" }
                val plan = BuildModelReader.readRequest(File(request)).serverDeployments.single { it.spec.node == spec.node }
                require(plan.spec == spec && (requested == "rollback" || plan.image == image.image)) { "Server declaration or artifact changed after planning" }
                if (requested == "rollback") {
                    val receipts = WorkerDeployKernel.receipts(root, spec.node).filter { it.environment == spec.environment && it.result == ReceiptStatus.Succeeded }
                    val latest = receipts.first { it.remoteVersion == plan.previousDigest && it.action in listOf("apply", "rollback") }
                    val previous = receipts.first { it.remoteVersion == latest.rollbackTarget }
                    ServerRolloutKernel.rollback(plan, latest, previous, run, record)
                } else ServerRolloutKernel.apply(plan, run, record)
            }
            else -> error("Unsupported server rollout action")
        }
    }
}
