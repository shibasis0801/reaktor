package dev.shibasis.reaktor.cloud

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * Drives the repo's existing TypeScript Dagger module via the `dagger` CLI (`dagger call <fn>`).
 * The module runs containerized by the Dagger engine — language-agnostic, no host Node. The CLI
 * is a Go binary the JVM supervises as a subprocess.
 */
class DaggerToolProvider(
    private val repoRoot: File,
    private val runner: ProcessToolRunner = ProcessToolRunner(),
) : CloudToolProvider {
    override val id = "dagger"

    private val pending = mutableMapOf<String, List<String>>()

    override suspend fun operations(): List<CloudOperation> =
        listOf("pr", "reaktorVerify", "appChecks", "architectureChecks", "workerBundle", "deployWorkers", "webChecks", "desktopPackage")
            .map { fn -> CloudOperation(id, "dagger.$fn", fn, destructive = fn.startsWith("deploy")) }

    override suspend fun run(command: CloudCommand): CloudRun {
        val runId = "dagger-${command.fn}-${System.currentTimeMillis()}"
        val args = buildList {
            add("dagger"); add("call"); add(command.fn); add("--source"); add(".")
            command.args.forEach { (key, value) -> add("--$key"); add(value) }
        }
        pending[runId] = args
        return CloudRun(runId, command.operationId, RunStatus.Pending, System.currentTimeMillis())
    }

    override fun events(runId: String): Flow<CloudEvent> =
        pending[runId]?.let { runner.run(it, repoRoot, runId) } ?: emptyFlow()
}
