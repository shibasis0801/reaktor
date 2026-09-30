package dev.shibasis.reaktor.cloud

import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.ProcessDefinitionDirectory
import dev.shibasis.reaktor.tooling.ProcessDefinitionSeal
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

/**
 * Drives the repo's existing TypeScript Dagger module via the `dagger` CLI (`dagger call <fn>`).
 * The module runs containerized by the Dagger engine — language-agnostic, no host Node. The CLI
 * is a Go binary the JVM supervises as a subprocess.
 */
class DaggerToolProvider(
    private val repoRoot: File,
    private val runner: ProcessToolRunner = ProcessToolRunner(),
    private val executable: String = "dagger",
    private val reaktorRoot: File = repoRoot.resolve("../reaktor").canonicalFile,
    private val environment: Map<String, String> = System.getenv(),
) : CloudToolProvider {
    override val id = "dagger"

    private val runRegistry = CloudProcessRunRegistry(repoRoot, runner)

    override suspend fun operations(): List<CloudOperation> =
        DECLARED_FUNCTIONS
            .map { fn ->
                CloudOperation(
                    provider = id,
                    id = "dagger.$fn",
                    label = fn,
                    safety = if (fn.startsWith("deploy")) {
                        SafetyClass.ProductionReversibleWrite
                    } else {
                        SafetyClass.LocalArtifactWrite
                    },
                    inputs = if (fn == "deployWorkers") DEPLOY_INPUTS else emptyList(),
                    unavailableReason = if (fn == "deployWorkers") {
                        DEPLOY_WORKERS_UNAVAILABLE_REASON
                    } else {
                        null
                    },
                )
            }

    override suspend fun run(
        command: CloudCommand,
        approval: CloudExecutionApproval?,
    ): CloudRun {
        val invocation = invocation(command)
        val definitionSeal = daggerDefinitionSeal()
        val definitionDigest = definitionSeal.digest
        val plan = runner.plan(
            command.operationId, invocation.argv, repoRoot, invocation.safety,
            invocation.environment, invocation.sensitiveEnvironmentKeys, definitionDigest, definitionSeal,
        )
        require(approval == null || approval.planFingerprint == plan.fingerprint) {
            "Dagger approval does not match the exact reviewed command"
        }
        val runId = "dagger-${command.fn}-${UUID.randomUUID()}"
        runRegistry.register(
            runId, invocation.argv, invocation.safety, approval,
            invocation.environment, invocation.sensitiveEnvironmentKeys, definitionDigest, definitionSeal,
        )
        return CloudRun(runId, command.operationId, RunStatus.Pending, System.currentTimeMillis())
    }

    override suspend fun plan(command: CloudCommand): CloudExecutionPlan {
        val invocation = invocation(command)
        val definitionSeal = daggerDefinitionSeal()
        val definitionDigest = definitionSeal.digest
        return runner.plan(
            command.operationId, invocation.argv, repoRoot, invocation.safety,
            invocation.environment, invocation.sensitiveEnvironmentKeys, definitionDigest, definitionSeal,
        )
    }

    private fun daggerDefinitionSeal(): ProcessDefinitionSeal = ProcessDefinitionSeal.capture(
        files = listOf(repoRoot.resolve("dagger.json")).filter(File::isFile),
        directories = listOf(repoRoot.resolve(".dagger")).filter(File::isDirectory)
            .map(::ProcessDefinitionDirectory),
    )

    private suspend fun invocation(command: CloudCommand): DaggerInvocation {
        require(command.provider == id) { "Dagger provider cannot run '${command.provider}' commands" }
        require(command.fn in DECLARED_FUNCTIONS) { "Dagger function '${command.fn}' is not declared" }
        require(command.operationId == "$id.${command.fn}") {
            "Dagger operation '${command.operationId}' does not match function '${command.fn}'"
        }
        require(command.stack == null) { "Dagger commands do not accept a Pulumi stack" }
        val operation = operations().single { it.id == command.operationId }
        operation.unavailableReason?.let { reason ->
            error("Dagger function '${command.fn}' is unavailable: $reason")
        }
        val declaredInputs = operation.inputs.associateBy(CloudInput::key)
        val unknownInputs = command.args.keys - declaredInputs.keys
        require(unknownInputs.isEmpty()) { "Dagger command has undeclared inputs: ${unknownInputs.sorted()}" }
        operation.inputs.filter(CloudInput::required).forEach { input ->
            require(!command.args[input.key].isNullOrBlank()) { "Dagger input '${input.key}' is required" }
        }
        val sensitiveInputs = command.args.filterKeys { declaredInputs.getValue(it).sensitive }
        sensitiveInputs.forEach { (key, environmentName) ->
            require(ENVIRONMENT_NAME.matches(environmentName)) {
                "Dagger sensitive input '$key' must name an environment variable"
            }
            require(!environment[environmentName].isNullOrBlank()) {
                "Dagger sensitive input '$key' references missing environment variable '$environmentName'"
            }
        }
        command.args["target-env"]?.let { targetEnvironment ->
            require(targetEnvironment in setOf("dev", "prod")) {
                "Dagger input 'target-env' must be one of: dev, prod"
            }
        }
        require(reaktorRoot.isDirectory) {
            "Dagger Reaktor source directory does not exist: ${reaktorRoot.absolutePath}"
        }
        val args = buildList {
            add(executable); add("call"); add(command.fn); add("--source"); add(".")
            add("--reaktor"); add(reaktorRoot.absolutePath)
            command.args.toSortedMap().filterKeys { !declaredInputs.getValue(it).sensitive }
                .forEach { (key, value) -> add("--$key"); add(value) }
            command.args.toSortedMap().filterKeys { declaredInputs.getValue(it).sensitive }
                .forEach { (key, value) -> add("--$key"); add("env:$value") }
        }
        val safety = when {
            command.fn.startsWith("deploy", ignoreCase = true) -> SafetyClass.ProductionReversibleWrite
            else -> SafetyClass.LocalArtifactWrite
        }
        return DaggerInvocation(
            args,
            safety,
            environment = sensitiveInputs.values.associateWith { environment.getValue(it) },
            sensitiveEnvironmentKeys = sensitiveInputs.values.toSet(),
        )
    }

    override fun events(runId: String): Flow<CloudEvent> = runRegistry.events(runId)

    private companion object {
        val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        const val DEPLOY_WORKERS_UNAVAILABLE_REASON =
            "Disabled until the full BestBuds/Reaktor source closure is fingerprint-bound"
        val DEPLOY_INPUTS = listOf(
            CloudInput(
                key = "cloudflare-api-token",
                label = "Cloudflare token environment variable",
                required = true,
                default = "CLOUDFLARE_API_TOKEN",
                sensitive = true,
            ),
            CloudInput(
                key = "target-env",
                label = "Target environment",
                required = true,
                default = "prod",
                allowedValues = listOf("dev", "prod"),
            ),
        )
        val DECLARED_FUNCTIONS = listOf(
            "pr",
            "reaktorVerify",
            "appChecks",
            "jsChecks",
            "architectureChecks",
            "workerBundle",
            "deployWorkers",
            "webChecks",
            "desktopPackage",
        )
    }

    private data class DaggerInvocation(
        val argv: List<String>,
        val safety: SafetyClass,
        val environment: Map<String, String>,
        val sensitiveEnvironmentKeys: Set<String>,
    )
}

internal fun cloudDefinitionDigest(vararg roots: File): String = java.security.MessageDigest.getInstance("SHA-256").let { digest ->
    roots.asSequence().filter(File::exists).flatMap { root ->
        if (root.isFile) sequenceOf(root) else root.walkTopDown().filter(File::isFile)
    }.filterNot { file -> file.path.contains("${File.separator}build${File.separator}") || file.path.contains("${File.separator}.git${File.separator}") }
        .sortedBy(File::getAbsolutePath)
        .forEach { file ->
            digest.update(file.canonicalPath.toByteArray())
            digest.update(0)
            digest.update(file.readBytes())
        }
    digest.digest().joinToString("") { "%02x".format(it) }
}
