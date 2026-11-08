package dev.shibasis.reaktor.cluster

import dev.shibasis.reaktor.cluster.build.BuildControl
import dev.shibasis.reaktor.cluster.build.RouterState
import java.nio.file.Path
import kotlinx.serialization.Serializable

@Serializable
data class ClusterJobRequest(val job: String)

@Serializable
data class ClusterJobMutation(val job: String, val executionId: String)

@Serializable
data class ClusterJobAction(val supported: Boolean = false, val reason: String? = null)

@Serializable
data class ClusterJobActions(
    val stop: ClusterJobAction = ClusterJobAction(),
    val resume: ClusterJobAction = ClusterJobAction(),
)

@Serializable
data class ClusterJobControl(
    val schemaVersion: Int = 1,
    val runId: String,
    val host: String? = null,
    val status: String,
    val executionId: String? = null,
    val actions: ClusterJobActions = ClusterJobActions(),
    val message: String? = null,
)

class ClusterControls(
    private val state: Path = ClusterLocations.state(),
    private val commands: ClusterCommands = SystemClusterCommands,
) {
    fun inspect(request: ClusterJobRequest): ClusterJobControl {
        require(request.job.matches(Job)) { "Invalid build identity" }
        return try { BuildControl(RouterState(state), commands).inspect(request.job) }
        catch (refused: Exception) { if (refused is InterruptedException) throw refused else failure(request.job, null, refused) }
    }

    fun stop(request: ClusterJobMutation): ClusterJobControl = mutate("stop", request)

    fun resume(request: ClusterJobMutation): ClusterJobControl = mutate("resume", request)

    private fun mutate(action: String, request: ClusterJobMutation): ClusterJobControl {
        require(request.executionId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{7,127}"))) { "Invalid execution identity" }
        val current = inspect(ClusterJobRequest(request.job))
        require(current.executionId == request.executionId) { "Execution changed; refresh this build before acting" }
        val capability = if (action == "stop") current.actions.stop else current.actions.resume
        require(capability.supported) { capability.reason ?: "This build does not support $action" }
        check(action == "stop") { capability.reason ?: "This build does not support $action" }
        return BuildControl(RouterState(state), commands).stop(request.job, request.executionId)
    }

    private fun failure(job: String, execution: String?, error: Exception) = ClusterJobControl(runId = job, status = "Unknown", executionId = execution,
        actions = ClusterJobActions(ClusterJobAction(false, error.message), ClusterJobAction(false, error.message)), message = error.message)

    private companion object {
        val Job = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,179}")
    }
}
