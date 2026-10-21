package dev.shibasis.reaktor.cloud

import dev.shibasis.dependeasy.model.KernelSpec
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.ProcessDefinitionDirectory
import dev.shibasis.reaktor.tooling.ProcessDefinitionSeal
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

class PulumiToolProvider(
    private val programDir: File,
    private val tool: KernelSpec.InstallTool,
    private val runner: ProcessToolRunner = ProcessToolRunner(),
) : CloudToolProvider {
    override val id = "pulumi"

    private val runRegistry = CloudProcessRunRegistry(programDir, runner)

    override suspend fun operations(): List<CloudOperation> = listOf(
        CloudOperation(
            id,
            "pulumi.preview",
            "preview",
            inputs = listOf(CloudInput("stack", "Pulumi stack", required = true)),
            safety = SafetyClass.LiveRead,
        ),
        CloudOperation(
            id,
            "pulumi.refresh",
            "refresh",
            inputs = listOf(CloudInput("stack", "Pulumi stack", required = true)),
            safety = SafetyClass.ProductionReversibleWrite,
        ),
        CloudOperation(
            id,
            "pulumi.up",
            "up",
            inputs = listOf(CloudInput("stack", "Pulumi stack", required = true)),
            safety = SafetyClass.ProductionReversibleWrite,
        ),
    )

    override suspend fun run(
        command: CloudCommand,
        approval: CloudExecutionApproval?,
    ): CloudRun {
        val invocation = invocation(command)
        val definitionSeal = programDefinitionSeal()
        val definitionDigest = definitionSeal.digest
        val plan = runner.plan(
            command.operationId, invocation.argv, programDir, invocation.safety,
            definitionDigest = definitionDigest, definitionSeal = definitionSeal,
        )
        require(approval == null || approval.planFingerprint == plan.fingerprint) {
            "Pulumi approval does not match the exact reviewed stack and command"
        }
        val runId = "pulumi-${command.fn}-${UUID.randomUUID()}"
        runRegistry.register(
            runId, invocation.argv, invocation.safety, approval, definitionDigest = definitionDigest,
            definitionSeal = definitionSeal,
        )
        return CloudRun(runId, command.operationId, RunStatus.Pending, System.currentTimeMillis())
    }

    override suspend fun plan(command: CloudCommand): CloudExecutionPlan {
        val invocation = invocation(command)
        val definitionSeal = programDefinitionSeal()
        return runner.plan(
            command.operationId, invocation.argv, programDir, invocation.safety,
            definitionDigest = definitionSeal.digest,
            definitionSeal = definitionSeal,
        )
    }

    private fun invocation(command: CloudCommand): PulumiInvocation {
        require(command.provider == id) { "Pulumi provider cannot run '${command.provider}' commands" }
        require(command.fn in DECLARED_COMMANDS) { "Pulumi command '${command.fn}' is not declared" }
        require(command.operationId == "$id.${command.fn}") {
            "Pulumi operation '${command.operationId}' does not match command '${command.fn}'"
        }
        require(command.args.isEmpty()) { "Pulumi adapter does not declare arbitrary command arguments" }
        require(!command.stack.isNullOrBlank()) {
            "Pulumi command '${command.operationId}' requires an explicit stack"
        }
        val args = buildList {
            val directory = File(tool.installation)
            val executable = directory.resolve(tool.executable)
            require(tool.executable == "pulumi" && tool.checksum?.matches(Regex("[0-9a-f]{64}")) == true &&
                executable.isFile && executable.canExecute() &&
                directory.resolve(".sha256").takeIf(File::isFile)?.readText() == tool.checksum) {
                "Pulumi needs its checksum-qualified Dependeasy tool installation"
            }
            add(executable.canonicalPath); add(command.fn)
            add("--stack"); add(command.stack)
            add("--non-interactive")
            if (command.fn == "up" || command.fn == "refresh" || command.fn == "destroy") add("--yes")
        }
        val safety = when (command.fn) {
            "preview" -> SafetyClass.LiveRead
            "refresh", "up" -> SafetyClass.ProductionReversibleWrite
            else -> error("unreachable: declared Pulumi command has no safety class")
        }
        return PulumiInvocation(args, safety)
    }

    override fun events(runId: String): Flow<CloudEvent> = runRegistry.events(runId)

    private fun programDefinitionSeal(): ProcessDefinitionSeal = ProcessDefinitionSeal.capture(
        directories = listOf(ProcessDefinitionDirectory(programDir)),
    )

    private companion object {
        val DECLARED_COMMANDS = setOf("preview", "refresh", "up")
    }

    private data class PulumiInvocation(val argv: List<String>, val safety: SafetyClass)
}
