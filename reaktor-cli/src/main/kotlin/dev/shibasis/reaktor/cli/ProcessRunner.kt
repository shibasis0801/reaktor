package dev.shibasis.reaktor.cli

import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import dev.shibasis.reaktor.tooling.AdHocProcessPlan
import dev.shibasis.reaktor.tooling.OutputChannel
import dev.shibasis.reaktor.tooling.RunEvent
import dev.shibasis.reaktor.tooling.RunStatus
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.SafetyPolicy
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import dev.shibasis.reaktor.tooling.TaskId
import dev.shibasis.reaktor.tooling.TaskRun
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.io.File

/** Blocking CLI facade over the shared argv-only, process-tree-supervising executor. */
class ProcessRunner(
    private val terminal: Terminal,
    private val executor: SupervisedProcessExecutor = SupervisedProcessExecutor(),
) {
    fun run(
        command: List<String>,
        cwd: File,
        echo: Boolean = true,
        approvedBy: String? = null,
    ): Int {
        val request = request(command, cwd, approvedBy)
        return runPrepared(request, echo)
    }

    fun runPrepared(
        request: dev.shibasis.reaktor.tooling.ProcessExecutionRequest,
        echo: Boolean = true,
    ): Int {
        if (echo) terminal.println(dim("$ " + request.plan.displayCommand.joinToString(" ")))
        val result = execute(request) { event ->
            if (event is RunEvent.Output) {
                when (event.channel) {
                    OutputChannel.Stdout -> terminal.println(event.text)
                    OutputChannel.Stderr -> terminal.println(red(event.text))
                }
            }
        }
        val failure = result.failure
        if (failure != null && result.exitCode == null) {
            terminal.println(red("! ${failure.message}"))
        }
        return result.exitCode()
    }

    /** Run to completion and capture stdout and stderr in event order (used by `doctor`). */
    fun capture(command: List<String>, cwd: File): Pair<Int, String> {
        require(!requiresApproval(command)) { "capture cannot execute an operation that requires approval" }
        val output = StringBuilder()
        val result = execute(request(command, cwd, approvedBy = null)) { event ->
            if (event is RunEvent.Output) output.appendLine(event.text)
        }
        val failure = result.failure
        if (output.isEmpty() && failure != null) output.append(failure.message)
        return result.exitCode() to output.toString().trim()
    }

    private fun execute(
        request: dev.shibasis.reaktor.tooling.ProcessExecutionRequest,
        onEvent: (RunEvent) -> Unit,
    ): TaskRun = runBlocking {
        val handle = executor.start(request)
        handle.events.collect(onEvent)
        handle.await()
    }

    fun requiresApproval(command: List<String>): Boolean = classify(command).requiresApproval

    private fun request(
        command: List<String>,
        cwd: File,
        approvedBy: String?,
    ): dev.shibasis.reaktor.tooling.ProcessExecutionRequest {
        val safety = classify(command)
        val redactions = command.sensitiveArgumentValues()
        val now = System.currentTimeMillis()
        val common = { approval: dev.shibasis.reaktor.tooling.SafetyApproval? -> AdHocProcessPlan.create(
            argv = command,
            workingDirectory = cwd,
            taskId = TaskId("cli/${command.firstOrNull()?.substringAfterLast('/') ?: "process"}"),
            safety = SafetyPolicy(
                classification = safety,
                reason = if (safety.requiresApproval) "Direct interactive CLI command may change external state" else null,
            ),
            approval = approval,
            redactions = redactions,
            nowEpochMillis = now,
        ) }
        val preview = common(null)
        val approval = approvedBy?.let { operator ->
            dev.shibasis.reaktor.tooling.SafetyApproval(
                approvedBy = operator,
                approvedAtEpochMillis = now,
                reason = "Explicit interactive CLI confirmation",
                planFingerprint = preview.plan.fingerprint,
            )
        }
        return common(approval)
    }

    fun redactedCommand(command: List<String>): String {
        val secrets = command.sensitiveArgumentValues()
        return command.joinToString(" ") { argument ->
            secrets.fold(argument) { value, secret -> value.replace(secret, "[REDACTED]") }
        }
    }

    private fun classify(command: List<String>): SafetyClass {
        val words = command.joinToString(" ").lowercase()
        return when {
            listOf("destroy", "delete", "purge", "teardown", " drop ").any(words::contains) -> SafetyClass.Destructive
            listOf("migrate", "migration").any(words::contains) -> SafetyClass.DataMigration
            listOf("secret", "credential", "rotate-key", "rotate_key", "provision", "datagrip").any(words::contains) -> SafetyClass.CredentialWrite
            listOf(
                "deploy", "publish", "release", "pulumi up", "wrangler deploy", "distribute", "firebase",
                "testflight", "appstore", "play:internal", "play:production", "r2:push", "radar:upgrade",
                "karate:", "k6:", "test:auth", "test:reaktorserver", "test:workers", "test:analyticsserver",
            ).any(words::contains) ->
                SafetyClass.UnknownRemoteEffect
            listOf(
                " status", " doctor", " inspect", " list", " logs", "git diff", "git status",
                "kubectl get", "kubectl logs", "--version", " -version", " whoami", " functions", "stack ls", " preview",
            ).any(words::contains) -> SafetyClass.ReadOnly
            command.firstOrNull()?.portableBasename() in setOf("reaktor", "reaktor.bat") && command.getOrNull(1) == "install" ->
                SafetyClass.LocalArtifactWrite
            command.firstOrNull()?.portableBasename() in setOf("gradlew", "gradlew.bat") &&
                command.getOrNull(1) == "installDist" -> SafetyClass.LocalArtifactWrite
            command.any { it == "-jar" } && command.any {
                it.replace('\\', '/').endsWith("/targets/reaktorDesktop/engine.jar")
            } ->
                SafetyClass.LocalEphemeral
            listOf(" build", " compile", " assemble", " bundle", " package", " test", " check", " lint").any(words::contains) -> SafetyClass.LocalArtifactWrite
            else -> SafetyClass.UnknownRemoteEffect
        }
    }

    private fun TaskRun.exitCode(): Int = when (status) {
        RunStatus.Succeeded -> 0
        RunStatus.Cancelled -> 130
        RunStatus.TimedOut -> 124
        else -> exitCode ?: 127
    }
}

private fun String.portableBasename(): String = substringAfterLast('/').substringAfterLast('\\')

private fun List<String>.sensitiveArgumentValues(): Set<String> {
    val arguments = this
    return buildSet {
    addAll(arguments.pulumiConfigSetSecretValues())
    arguments.forEachIndexed { index, argument ->
        val normalized = argument.lowercase().replace('_', '-').trimStart('-')
        val sensitiveFlag = listOf("token", "password", "secret", "api-key", "private-key", "credential")
            .any(normalized::contains)
        if (sensitiveFlag) {
            argument.substringAfter('=', missingDelimiterValue = "").takeIf(String::isNotBlank)?.let(::add)
            arguments.getOrNull(index + 1)?.takeUnless { it == "--" }?.takeIf(String::isNotBlank)?.let(::add)
        }
        if (argument == "--secret" && arguments.pulumiConfigSetSecretValues().isEmpty())
            arguments.getOrNull(index - 1)?.takeIf(String::isNotBlank)?.let(::add)
    }
    }
}

private fun List<String>.pulumiConfigSetSecretValues(): Set<String> {
    val config = indexOf("config")
    val set = indexOf("set")
    if (config < 0 || set < 0 || none { it == "--secret" || it.startsWith("--secret=") }) return emptySet()
    // Pulumi/Cobra accepts persistent flags at any position, including evolving value-taking
    // flags. Once a config-set is declared secret, conservatively redact every following value;
    // hiding the key/stack as well is preferable to leaking a newly shaped secret argv.
    return drop(set + 1).filterTo(linkedSetOf()) { it.isNotBlank() && it != "--" }
}
