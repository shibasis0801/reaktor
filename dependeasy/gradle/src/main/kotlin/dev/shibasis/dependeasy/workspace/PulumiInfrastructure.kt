package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import dev.shibasis.dependeasy.toolchain.pulumiTool
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import kotlinx.serialization.encodeToString

@DisableCachingByDefault(because = "Pulumi preview observes the current stack state")
abstract class PulumiInfrastructure : DefaultTask(), KernelSpecOwner {
    @get:Input abstract val declaration: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val rootDirectory: DirectoryProperty
    @get:Input abstract val processPath: Property<String>
    @get:Input abstract val action: Property<String>
    @get:Input abstract val requestedEnvironment: Property<String>
    @get:Input @get:Optional abstract val protocolAction: Property<String>
    @get:Input @get:Optional abstract val protocolRequest: Property<String>
    @get:Inject abstract val execOperations: ExecOperations
    @get:Internal val spec: PulumiStackSpec get() = BuildModelReader.json.decodeFromString(declaration.get())
    @get:Internal override val kernelSpec get() = KernelSpec.PulumiStack(spec)
    @get:Internal override val kernelEffect get() = Effect.Deploy
    @get:Internal override val kernelHost get() = HostRequirement(HostPlatform.Portable, ExecutionHost.Local)

    @Option(option = "deploy-action", description = "Preview or apply the declared Pulumi stack")
    fun deployAction(value: String) { require(value in listOf("plan", "apply")); action.set(value) }

    init {
        action.convention("apply")
        protocolAction.convention(project.providers.gradleProperty(GradleProtocol.DeployActionOption))
        protocolRequest.convention(project.providers.gradleProperty(GradleProtocol.DeployRequestOption))
        rootDirectory.convention(project.rootProject.layout.projectDirectory)
        processPath.convention(project.option(BuildOption.environment("PATH", "")))
        outputs.upToDateWhen { false }
    }

    @TaskAction fun infrastructure() {
        val root = rootDirectory.get().asFile
        val spec = spec
        require(requestedEnvironment.get() == spec.environment.name) { "Pulumi stack does not declare the requested environment" }
        val missingSecrets = spec.stack.secretNames.filter { System.getenv(it).isNullOrEmpty() }
        val secrets = if (missingSecrets.isEmpty()) emptyMap() else try {
            WorkerSecretsKernel.values(missingSecrets, spec.stack.secretSources, root)
        } catch (_: Exception) { error("Cannot read the named Pulumi secrets; credential details withheld") }
        val run = UUID.randomUUID().toString()
        val record: (DeployReceipt) -> Unit = { WorkerDeployKernel.record(root, it) }
        val execute: (ProcessExecution) -> String = { command ->
            val output = ByteArrayOutputStream()
            val result = execOperations.exec {
                workingDir = File(command.directory); executable = command.executable; args(command.arguments)
                setEnvironment(BuildOption.environmentSnapshot().filterKeys { it in command.environmentKeys })
                environment(secrets.filterKeys { it in command.environmentKeys })
                environment("PULUMI_BACKEND_URL", if (java.net.URI(spec.stack.backend).scheme == null)
                    root.resolve(spec.stack.backend).toPath().toUri().toString() else spec.stack.backend)
                environment("REAKTOR_WORKSPACE_ROOT", root.absolutePath)
                environment("PATH", File(command.executable).parent + File.pathSeparator + processPath.get())
                environment("PULUMI_SKIP_UPDATE_CHECK", "true")
                standardOutput = output; errorOutput = OutputStream.nullOutputStream(); isIgnoreExitValue = true
            }
            require(result.exitValue == 0) { "Pulumi failed; provider output withheld" }
            output.toString(Charsets.UTF_8)
        }
        when (protocolAction.orNull ?: action.get()) {
            "plan" -> {
                val revision = ByteArrayOutputStream()
                execOperations.exec { workingDir = root; executable = "git"; args("rev-parse", "HEAD")
                    standardOutput = revision; errorOutput = OutputStream.nullOutputStream() }.assertNormalExitValue()
                val plan = PulumiStackKernel.plan(spec, root, revision.toString().trim(), java.net.InetAddress.getLocalHost().hostName, execute)
                PulumiStackKernel.store(root, plan); PulumiStackKernel.observe(plan, run, record)
                logger.lifecycle("Pulumi {}: {}", spec.node, plan.changes)
            }
            "apply" -> {
                val request = requireNotNull(protocolRequest.orNull) { "Pulumi apply requires a reviewed build plan" }
                val plan = BuildModelReader.readRequest(File(request)).infrastructure.single { it.spec.node == spec.node }
                require(plan.spec == spec) { "Pulumi declaration changed after planning" }
                PulumiStackKernel.apply(plan, root, run, execute, record)
            }
            else -> error("Unsupported Pulumi action")
        }
    }
}

internal fun Project.pulumiInfrastructure(name: String, stack: PulumiStack, program: OutputHandle? = null): NodeHandle {
    require(program == null || program.kind == OutputKind.JvmDistribution) { "Pulumi consumes a JVM distribution" }
    val programFiles = program?.let(::outputFiles)
    val programDirectory = provider { programFiles?.singleFile?.absolutePath.orEmpty() }
    val environment = option(BuildOption.string(GradleProtocol.EnvironmentOption, stack.stacks.keys.first().name))
    val tool = pulumiTool()
    val site = declarationSite()
    val id = NodeId(rootProject.name, path, name)
    val ownerDirectory = rootProject.projectDir
    val declarationStack = if (program == null) stack else stack.copy(directory =
        "build/dependeasy/contracts/pulumi/projects/" + BuildModelReader.digest(id.toString()))
    val infrastructure = tasks.register(name, PulumiInfrastructure::class.java) {
        group = "delivery"
        requestedEnvironment.set(environment)
        declaration.set(environment.zip(tool.flatMap { it.destination.file("pulumi") }) { selected, binary ->
            selected to binary.asFile.absolutePath
        }.zip(programDirectory) { (selected, binary), distribution ->
            BuildModelReader.json.encodeToString(PulumiStackSpec(id, stack.stacks.keys.singleOrNull { it.name == selected } ?: stack.stacks.keys.first(), declarationStack,
                ProcessExecution(ownerDirectory.resolve(declarationStack.directory).absolutePath, binary, emptyList(),
                    (listOf("PATH", "HOME", "USER", "JAVA_HOME", "PULUMI_HOME") + stack.secretNames).distinct(),
                    ToolRef("pulumi", ToolchainVersions.Pulumi), stack.sources.paths + listOf(distribution).filter(String::isNotBlank), emptyList()),
                distribution.takeIf(String::isNotBlank))) })
        dependsOn(tool)
        sources.from(stack.sources.paths.map { rootProject.file(it) })
        programFiles?.let { sources.from(it) }
        site?.let { extensions.add(SourceSite::class.java, "declarationSite", it) }
    }
    return node(infrastructure).also { node ->
        val workspace = BuildWorkspace.get(this)
        program?.let { workspace.consume(node, it) }
        workspace.alias(rootDir, name, node)
    }
}
