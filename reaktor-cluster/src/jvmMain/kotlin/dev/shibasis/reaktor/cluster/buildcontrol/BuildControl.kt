package dev.shibasis.reaktor.cluster.build

import dev.shibasis.reaktor.cluster.ClusterCommands
import dev.shibasis.reaktor.cluster.ClusterJobAction
import dev.shibasis.reaktor.cluster.ClusterJobActions
import dev.shibasis.reaktor.cluster.ClusterJobControl
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

data class ProcessIdentity(val pid: Int, val started: String, val pgid: Int)

data class JobState(val value: JsonObject, val status: String, val stopReason: String?)

class BuildControl(private val router: RouterState, private val commands: ClusterCommands) {
    fun inspect(runId: String): ClusterJobControl {
        val (_, saved, worktree) = receipt(runId)
        var result = ClusterJobControl(runId = runId, host = saved.text("worker"), status = "Unknown", actions = ClusterJobActions(
            stop = ClusterJobAction(false, "Execution identity has not been verified"),
            resume = ClusterJobAction(false, "Collection recovery remains available through the existing Terminal resume command")))
        return try {
            val recorded = control(runId, saved, worktree)
            result = result.copy(executionId = recorded.execution)
            val job = site(saved).inspection(recorded.job, recorded.execution)
            val reason = if (complete(saved)) "Retained router receipt is already complete" else job.stopReason
            result.copy(status = job.status, actions = result.actions.copy(stop = ClusterJobAction(reason == null, reason)))
        } catch (refused: Exception) {
            when (refused) {
                is IllegalArgumentException, is IllegalStateException, is IOException -> result.copy(actions = result.actions.copy(stop = ClusterJobAction(false, refused.message)))
                else -> throw refused
            }
        }
    }

    fun stop(runId: String, execution: String): ClusterJobControl {
        val inspected = inspect(runId)
        require(execution == inspected.executionId) { "Displayed execution identity is stale or missing; inspect this run again" }
        require(inspected.actions.stop.supported) { inspected.actions.stop.reason ?: "This build cannot be stopped" }
        val (path, saved, worktree) = receipt(runId)
        val recorded = control(runId, saved, worktree)
        require(recorded.execution == execution) { "Execution identity changed since inspection" }
        require(!complete(saved)) { "Retained router receipt completed since inspection" }
        val finished = site(saved).stop(recorded.job, execution)
        if (finished.status == "Cancelled") {
            val latest = receipt(runId).saved
            ControlFiles.write(path, JsonObject(latest + mapOf("cancelled" to JsonPrimitive(true), "exit_code" to JsonPrimitive(130),
                "finished" to (finished.value["finished"] ?: JsonNull), "phase" to JsonPrimitive("Finished"), "phase_host" to (saved["worker"] ?: JsonNull))))
        }
        return inspected.copy(status = finished.status, actions = inspected.actions.copy(stop = ClusterJobAction(false, "Execution already finished")),
            message = if (finished.status == "Cancelled") "Execution cancellation confirmed" else "Execution finished before cancellation")
    }

    private data class Receipt(val path: Path, val saved: JsonObject, val worktree: String?)

    private data class Recorded(val job: String, val execution: String)

    private fun receipt(runId: String): Receipt {
        require(RunId.matches(runId)) { "Invalid retained run identity" }
        val path = ControlFiles.ownedDirectory(router.state.resolve("logs")).resolve("$runId.json")
        val saved = ControlFiles.read(path)
        require(saved.text("run_id") == runId) { "Receipt identity does not match its retained file" }
        val workspace = saved.text("workspace_root")?.let(Path::of)
        require(workspace != null && workspace.isAbsolute && workspace.normalize() == workspace && canonical(workspace)) { "Receipt has no canonical source workspace" }
        val project = saved.text("project")
        require(project != null && project in router.projects) { "Receipt project is not configured" }
        val worktree = if (workspace != router.workspace && snapshotHost(saved) == null)
            requireNotNull(router.worktree(workspace, project, commands)) { "Receipt workspace is not the configured source workspace" } else null
        return Receipt(path, saved, worktree)
    }

    private fun canonical(path: Path): Boolean {
        val existing = generateSequence(path) { it.parent }.first { Files.exists(it) }
        return existing.toRealPath().resolve(existing.relativize(path)) == path
    }

    private fun snapshotHost(saved: JsonObject): String? {
        val expected = if (saved.text("worker") == "local") "local" else "worker"
        val folder = if (expected == "local" && workerSlot(saved["local_slot"]) == 1) "local-1" else expected
        return expected.takeIf { router.slotRoots().any { saved.text("workspace_root") == it.resolve(folder).toString() } }
    }

    private fun control(runId: String, saved: JsonObject, worktree: String?): Recorded {
        val recorded = saved["execution_control"] as? JsonObject
        require(recorded != null && (recorded["schemaVersion"] as? JsonPrimitive)?.intOrNull == 1) {
            "This older receipt has no verified supervisor identity; Stop is unavailable"
        }
        val host = saved.text("worker")
        require(recorded.text("host") == host && (host == "local" || host == router.host)) { "Receipt execution host does not match configured local or worker host" }
        val expected = if (host == "local") router.state.resolve("jobs").resolve(runId) else {
            val index = workerSlot(saved["worker_slot"])
            require(index != null && index in 0 until MaxWorkerSlots) { "Receipt worker slot is not configured" }
            val root = if (snapshotHost(saved) == "worker") "${router.remote}/.remote-dev/slots/worker" else router.slotRemote(index, worktree)
            val original = saved["remote_root"]
            require(if (original == null || original is JsonNull) root.split('/').none { it.endsWith(".noindex") } else saved.text("remote_root") == root) {
                "Receipt names a worker folder outside its configured slot"
            }
            Path.of(root, ".remote-dev", "jobs", runId)
        }
        val execution = recorded.text("executionId")
        require(recorded.text("jobDirectory") == expected.toString() && execution != null) { "Receipt control directory or execution identity is invalid" }
        return Recorded(expected.toString(), execution)
    }

    private fun workerSlot(slot: JsonElement?): Int? = when {
        slot == null || slot is JsonNull || (slot as? JsonPrimitive)?.booleanOrNull == false -> 0
        slot is JsonPrimitive && !slot.isString && slot.booleanOrNull == null -> slot.intOrNull
        else -> null
    }

    private fun complete(saved: JsonObject): Boolean = truthy(saved["finished"]) || saved["exit_code"].let { it != null && it !is JsonNull }

    private fun truthy(value: JsonElement?): Boolean = when (value) {
        null, is JsonNull -> false
        is JsonPrimitive -> if (value.isString) value.content.isNotEmpty() else value.booleanOrNull ?: (value.content.toDoubleOrNull() != 0.0)
        else -> true
    }

    private fun site(saved: JsonObject): JobSite = if (saved.text("worker") == "local") LocalJobs(commands) else WorkerJobs(router.host, commands)

    companion object {
        const val MaxWorkerSlots = 3
        private val RunId = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,199}")
    }
}
