package dev.shibasis.reaktor.cluster.build

import dev.shibasis.reaktor.cluster.ClusterCommands
import dev.shibasis.reaktor.cluster.SystemClusterCommands
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class RouterFixture private constructor(val root: Path) {
    val state: Path = Files.createDirectories(root.resolve(".remote-dev/logs")).parent
    val worker: Path = Files.createDirectories(root.resolve("worker"))

    init {
        Files.createDirectories(root.resolve(Project))
        ControlFiles.write(state.resolve("config.json"), buildJsonObject {
            put("host", WorkerHost)
            put("remote_root", worker.toString())
            putJsonArray("projects") { add(Project) }
        })
    }

    fun control(commands: ClusterCommands = SystemClusterCommands) = BuildControl(RouterState(state), commands)

    fun receipt(run: String): JsonObject = ControlFiles.read(state.resolve("logs/$run.json"))

    fun link(at: Path, target: Path): Path = Files.createSymbolicLink(at, target)

    fun local(run: String, receipt: Map<String, JsonElement> = emptyMap()) = job(run, "local", state.resolve("jobs/$run"), receipt)

    fun worker(run: String, receipt: Map<String, JsonElement> = emptyMap()) =
        job(run, WorkerHost, worker.resolve(".remote-dev/jobs/$run"), mapOf("remote_root" to JsonPrimitive(worker.toString())) + receipt)

    private fun job(run: String, host: String, directory: Path, receipt: Map<String, JsonElement>): Job {
        val execution = "execution-$run"
        Files.createDirectories(directory)
        ControlFiles.write(directory.resolve("request.json"), buildJsonObject {
            put("runId", run)
            put("executionId", execution)
            putJsonArray("argv") { add("/bin/sleep"); add("60") }
            put("cwd", root.toString())
        })
        val written = buildJsonObject {
            put("run_id", run)
            put("project", Project)
            put("worker", host)
            put("workspace_root", root.toString())
            put("exit_code", JsonNull)
            putJsonObject("execution_control") {
                put("schemaVersion", 1)
                put("executionId", execution)
                put("host", host)
                put("jobDirectory", directory.toString())
            }
        }
        ControlFiles.write(state.resolve("logs/$run.json"), JsonObject(written + receipt))
        return Job(run, execution, directory)
    }

    class Job(val run: String, val execution: String, val directory: Path) {
        val stopRequested: Boolean get() = Files.exists(directory.resolve("stop.json"))

        fun control(): JsonObject = ControlFiles.read(directory.resolve("control.json"))

        fun status(): String? = runCatching { control().text("status") }.getOrNull()

        fun <T> supervised(block: (Process) -> T): T {
            val supervisor = ProcessBuilder("python3", Supervisor.toString(), "_supervise", directory.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            try {
                val deadline = System.nanoTime() + 10_000_000_000
                while (status() != "Running") {
                    check(supervisor.isAlive && System.nanoTime() < deadline) { "The supervisor did not start its execution" }
                    Thread.sleep(50)
                }
                return block(supervisor)
            } finally {
                if (supervisor.isAlive) ControlFiles.write(directory.resolve("stop.json"), buildJsonObject { put("executionId", execution) })
                if (!supervisor.waitFor(10, TimeUnit.SECONDS)) supervisor.destroyForcibly()
            }
        }
    }

    companion object {
        const val WorkerHost = "fixture-worker"
        const val Project = "app"

        val Supervisor: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("reaktor-cluster/tooling/build_control.py") }.first(Files::isRegularFile)

        fun <T> use(block: (RouterFixture) -> T): T {
            val root = Files.createTempDirectory("router-").toRealPath()
            try {
                return block(RouterFixture(root))
            } finally {
                root.toFile().deleteTreeSafely(root.parent.toFile())
            }
        }
    }
}
