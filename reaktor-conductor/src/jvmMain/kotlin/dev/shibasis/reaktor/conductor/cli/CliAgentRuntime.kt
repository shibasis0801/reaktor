package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.AgentEvent
import dev.shibasis.reaktor.conductor.AgentOutcome
import dev.shibasis.reaktor.conductor.AgentRequest
import dev.shibasis.reaktor.conductor.AgentRuntime
import dev.shibasis.reaktor.tooling.AdHocProcessPlan
import dev.shibasis.reaktor.tooling.OutputChannel
import dev.shibasis.reaktor.tooling.RunEvent
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.SafetyPolicy
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import dev.shibasis.reaktor.tooling.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Turns one harness's line protocol into [AgentEvent]s.
 *
 * Kept separate from process handling so it can be tested against real captured output without
 * spawning anything, which is the only way to know a parser matches a harness it does not own.
 */
interface CliEventParser {
    /** Called once per stdout line. Unknown shapes must be ignored, not fatal. */
    fun onLine(line: String): List<AgentEvent>

    fun finish(exitCode: Int, stderr: String): AgentOutcome
}

/**
 * Runs an agent harness as a supervised subprocess.
 *
 * Reuses `SupervisedProcessExecutor`, so an agent run is an ordinary Reaktor run: argv with no
 * shell, independent stdout and stderr line streams, whole-process-tree ownership on cancel and
 * timeout, secret redaction, and a plan fingerprint.
 */
abstract class CliAgentRuntime(
    private val executor: SupervisedProcessExecutor,
) : AgentRuntime {
    protected abstract fun argv(request: AgentRequest): List<String>

    protected abstract fun parser(request: AgentRequest): CliEventParser

    /**
     * A read-only agent is `ReadOnly`; one allowed to edit is `LocalArtifactWrite`. Neither needs
     * an approval in the shipped ladder, because neither can reach a deployment: an agent that
     * writes does so in the directory the caller handed it.
     */
    protected open fun safety(request: AgentRequest): SafetyPolicy =
        if (request.agent.tools.allowWrites) {
            SafetyPolicy(SafetyClass.LocalArtifactWrite, "agent may edit files in its workspace")
        } else {
            SafetyPolicy(SafetyClass.ReadOnly, "agent runs with writes disabled")
        }

    override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
        val parser = parser(request)
        val directory = File(request.workingDirectory)
        if (!directory.isDirectory) {
            emit(
                AgentEvent.Finished(
                    request.agent.id,
                    AgentOutcome(
                        agent = request.agent.id,
                        text = "",
                        ok = false,
                        failure = "Working directory does not exist: ${directory.absolutePath}",
                    ),
                ),
            )
            return@flow
        }

        val execution = AdHocProcessPlan.create(
            argv = argv(request),
            workingDirectory = directory,
            taskId = TaskId("conductor:${kind.name.lowercase()}:${request.agent.id.value}"),
            safety = safety(request),
            timeoutMillis = request.agent.budget.timeoutMillis,
        )

        val handle = executor.start(execution)
        val stderr = StringBuilder()
        var exitCode = -1

        handle.events.collect { event ->
            when (event) {
                is RunEvent.Output -> when (event.channel) {
                    OutputChannel.Stdout -> parser.onLine(event.text).forEach { emit(it) }
                    OutputChannel.Stderr -> stderr.append(event.text)
                }

                is RunEvent.Completed -> exitCode = event.run.exitCode ?: -1
                else -> Unit
            }
        }

        emit(AgentEvent.Finished(request.agent.id, parser.finish(exitCode, stderr.toString())))
    }
}

/** Trims one streamed line and reports whether it could carry a JSON object. */
internal fun String.jsonLineOrNull(): String? {
    val trimmed = trim()
    return if (trimmed.startsWith("{")) trimmed else null
}
