package dev.shibasis.reaktor.cluster.build

import dev.shibasis.reaktor.cluster.ClusterCommands
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.file.Path

sealed class JobSite(protected val commands: ClusterCommands) {
    abstract fun inspection(job: String, execution: String): JobState
    abstract fun stop(job: String, execution: String): JobState

    protected fun evaluate(value: JsonObject, live: (JsonObject?) -> Boolean): JobState {
        val status = value.text("status")
        val supervisor = value["supervisor"] as? JsonObject
        val process = value["process"] as? JsonObject
        val reason = when {
            status in Terminal -> "Execution already finished"
            status == "Stopping" && live(supervisor) -> "Cancellation already requested; waiting for the supervised execution to stop"
            status != "Running" && status != "Stopping" -> "Execution has no active supervised process"
            !live(supervisor) || !live(process) -> "Recorded supervisor or execution process is no longer live under its recorded birth identity"
            process.number("pgid") != process.number("pid") -> "Execution does not own an isolated process group"
            else -> null
        }
        val shown = if (status == "Running" && reason != null || status == "Stopping" && !live(supervisor)) "Unknown" else status ?: "Unknown"
        return JobState(value, shown, reason)
    }

    protected fun matching(job: String, execution: String, request: JsonObject, control: JsonObject): JsonObject {
        val run = Path.of(job).fileName.toString()
        require(request.text("executionId") == execution && request.text("runId") == run) { "Execution identity does not match this job" }
        require(control.text("executionId") == execution && control.text("runId") == run) { "Supervisor identity does not match this job" }
        return control
    }

    protected fun wait(read: () -> Pair<JsonObject, (JsonObject?) -> Boolean>, pause: Long): JobState {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            val (value, live) = read()
            if (value.text("status") in Terminal) return JobState(value, value.text("status")!!, null)
            if (!live(value["supervisor"] as? JsonObject)) {
                val settled = read().first
                check(settled.text("status") in Terminal) { "Supervisor exited before confirming cancellation" }
                return JobState(settled, settled.text("status")!!, null)
            }
            Thread.sleep(pause)
        }
        error("Cancellation was requested; supervisor has not confirmed completion within 20 seconds")
    }

    companion object {
        val Terminal = setOf("Succeeded", "Failed", "Cancelled")

        fun identity(fields: List<String>, pid: Int): ProcessIdentity? =
            if (fields.size != 7 || fields.last().startsWith("Z")) null else fields[5].toIntOrNull()?.let { ProcessIdentity(pid, fields.take(5).joinToString(" "), it) }

        fun recorded(identity: JsonObject?): ProcessIdentity? {
            val pid = identity.number("pid") ?: return null
            return ProcessIdentity(pid, identity?.text("started") ?: return null, identity.number("pgid") ?: return null)
        }

        private fun JsonObject?.number(key: String): Int? = (this?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    }
}

class LocalJobs(commands: ClusterCommands) : JobSite(commands) {
    override fun inspection(job: String, execution: String) = evaluate(state(job, execution), ::live)

    override fun stop(job: String, execution: String): JobState {
        evaluate(state(job, execution), ::live).stopReason?.let { throw IllegalArgumentException(it) }
        ControlFiles.write(Path.of(job).resolve("stop.json"), JsonObject(mapOf("executionId" to JsonPrimitive(execution))))
        return wait({ state(job, execution) to ::live }, 100)
    }

    private fun state(job: String, execution: String): JsonObject {
        val directory = ControlFiles.ownedDirectory(Path.of(job))
        return matching(job, execution, ControlFiles.read(directory.resolve("request.json")), ControlFiles.read(directory.resolve("control.json")))
    }

    private fun live(identity: JsonObject?): Boolean {
        val expected = recorded(identity) ?: return false
        if (expected.pid <= 1) return false
        val result = commands.run(listOf("/usr/bin/env", "LC_ALL=C", "/bin/ps", "-p", expected.pid.toString(), "-o", "lstart=", "-o", "pgid=", "-o", "stat="), 3)
        return result.exitCode == 0 && identity(result.output.trim().split(Whitespace), expected.pid) == expected
    }
}

class WorkerJobs(private val host: String, commands: ClusterCommands) : JobSite(commands) {
    override fun inspection(job: String, execution: String) = snapshot(job, execution).let { (value, live) -> evaluate(value, live) }

    override fun stop(job: String, execution: String): JobState {
        snapshot(job, execution).let { (value, live) -> evaluate(value, live) }.stopReason?.let { throw IllegalArgumentException(it) }
        require(Safe.matches(execution)) { "Invalid execution identity" }
        val written = ssh("${enter(job)} umask 077; t=\"stop.json.$$.tmp\"; printf '%s\\n' '{\"executionId\":\"$execution\"}' > \"\$t\" && mv -f \"\$t\" stop.json", 10)
        check(written.exitCode == 0) { "The worker refused the stop request" }
        return wait({ snapshot(job, execution) }, 500)
    }

    private fun snapshot(job: String, execution: String): Pair<JsonObject, (JsonObject?) -> Boolean> {
        val result = ssh(enter(job) + Files.joinToString(" ") { "[ -f $it ] && [ ! -L $it ] && [ -O $it ] && [ \"$(stat -f %z $it)\" -le 1048576 ] || { echo 'reaktor-refused'; exit 3; };" } +
            " printf '\\n$Request\\n'; cat request.json; printf '\\n$Control\\n'; cat control.json; printf '\\n$Processes\\n';" +
            " pids=$(grep -o '\"pid\": *[0-9]*' control.json | grep -o '[0-9]*$' | paste -sd, -);" +
            " [ -z \"\$pids\" ] || LC_ALL=C /bin/ps -o pid= -o lstart= -o pgid= -o stat= -p \"\$pids\" 2>/dev/null; exit 0", 15)
        require(result.exitCode == 0 && Request in result.output) { "The worker job directory is not an owned canonical directory with owned, bounded control files" }
        val request = Json.parseToJsonElement(result.output.substringAfter("\n$Request\n").substringBefore("\n$Control\n")) as JsonObject
        val control = Json.parseToJsonElement(result.output.substringAfter("\n$Control\n").substringBefore("\n$Processes\n")) as JsonObject
        val processes = result.output.substringAfter("\n$Processes\n").lines().mapNotNull { line ->
            val fields = line.trim().split(Whitespace)
            fields.firstOrNull()?.toIntOrNull()?.let { pid -> identity(fields.drop(1), pid) }
        }.associateBy { it.pid }
        return matching(job, execution, request, control) to { identity -> recorded(identity)?.let { it.pid > 1 && processes[it.pid] == it } == true }
    }

    private fun enter(job: String): String {
        require(Safe.matches(job) && job.startsWith("/")) { "Invalid worker job directory" }
        return "job='$job'; cd -P \"\$job\" 2>/dev/null && [ \"$(pwd -P)\" = \"\$job\" ] && [ -O . ] || { echo 'reaktor-refused'; exit 3; };"
    }

    private fun ssh(script: String, timeout: Long) = commands.run(listOf("ssh", "-o", "BatchMode=yes", host, script), timeout)

    private companion object {
        val Files = listOf("request.json", "control.json")
        const val Request = "reaktor-request"
        const val Control = "reaktor-control"
        const val Processes = "reaktor-processes"
        val Safe = Regex("[A-Za-z0-9/._-]+")
    }
}

private val Whitespace = Regex("\\s+")
