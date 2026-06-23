package dev.shibasis.reaktor.cloud

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * Drives a Pulumi program (e.g. reaktor-cloud's observability stack) via the `pulumi` CLI as a
 * supervised subprocess. JVM-native, no Node sidecar. (A future upgrade can swap this for the
 * in-process Pulumi Java Automation API when that artifact is wired in.)
 */
class PulumiToolProvider(
    private val programDir: File,
    private val runner: ProcessToolRunner = ProcessToolRunner(),
) : CloudToolProvider {
    override val id = "pulumi"

    private val pending = mutableMapOf<String, List<String>>()

    override suspend fun operations(): List<CloudOperation> = listOf(
        CloudOperation(id, "pulumi.preview", "preview"),
        CloudOperation(id, "pulumi.refresh", "refresh"),
        CloudOperation(id, "pulumi.up", "up", destructive = true),
    )

    override suspend fun run(command: CloudCommand): CloudRun {
        val runId = "pulumi-${command.fn}-${System.currentTimeMillis()}"
        val args = buildList {
            add("pulumi"); add(command.fn)
            command.stack?.let { add("--stack"); add(it) }
            add("--non-interactive")
            if (command.fn == "up" || command.fn == "refresh" || command.fn == "destroy") add("--yes")
        }
        pending[runId] = args
        return CloudRun(runId, command.operationId, RunStatus.Pending, System.currentTimeMillis())
    }

    override fun events(runId: String): Flow<CloudEvent> =
        pending[runId]?.let { runner.run(it, programDir, runId) } ?: emptyFlow()
}
