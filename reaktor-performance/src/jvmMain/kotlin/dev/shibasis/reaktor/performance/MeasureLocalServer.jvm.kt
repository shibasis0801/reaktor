package dev.shibasis.reaktor.performance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

enum class MeasureServerState { Running, Partial, Stopped, DockerDown }

data class MeasureServerStatus(val state: MeasureServerState, val detail: String, val dashboardUrl: String, val apiUrl: String)

class MeasureLocalServer private constructor(val ecosystem: Path) {
    private val state: Path = ecosystem.resolve(".state")

    suspend fun start(progress: (String) -> Unit = {}) = run("up", 45.minutes, progress)

    suspend fun stop(progress: (String) -> Unit = {}) = run("stop", 3.minutes, progress)

    suspend fun connect(): MeasureLocalConnection {
        run("connect", 2.minutes)
        return requireNotNull(connection()) { "The launcher did not write a connection" }
    }

    fun connection(): MeasureLocalConnection? = state.resolve("connection.json").takeIf(Files::isRegularFile)?.let {
        runCatching { Decoder.decodeFromString<MeasureLocalConnection>(Files.readString(it)) }.getOrNull()
    }

    suspend fun status(): MeasureServerStatus = withContext(Dispatchers.IO) {
        val known = connection()
        val api = known?.apiUrl ?: ApiUrl
        val dashboard = known?.dashboardUrl ?: DashboardUrl
        val answering = listOf("$api/ping", "${known?.ingestUrl ?: IngestUrl}/ping", dashboard).map(::answers)
        when {
            answering.all { it } -> MeasureServerStatus(MeasureServerState.Running, "Running on this Mac", dashboard, api)
            answering.any { it } -> MeasureServerStatus(MeasureServerState.Partial,
                "Some services are not answering yet: ${listOf("API", "ingest", "dashboard").filterIndexed { index, _ -> !answering[index] }.joinToString()}", dashboard, api)
            !dockerAnswers() -> MeasureServerStatus(MeasureServerState.DockerDown, "Docker Desktop is not running", dashboard, api)
            else -> MeasureServerStatus(MeasureServerState.Stopped, "Stopped. Recordings are kept.", dashboard, api)
        }
    }

    private suspend fun run(command: String, timeout: kotlin.time.Duration, progress: (String) -> Unit = {}) = coroutineScope {
        Files.createDirectories(state)
        val log = state.resolve("reaktor-$command.log")
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder("bash", ecosystem.resolve("measure.sh").toString(), command)
                .directory(ecosystem.toFile()).redirectErrorStream(true).start()
        }
        Live[process] = Unit
        val tail = ArrayDeque<String>()
        val reader = launch(Dispatchers.IO) {
            Files.newBufferedWriter(log).use { out ->
                process.inputStream.bufferedReader().forEachLine { line ->
                    out.appendLine(line)
                    out.flush()
                    val text = line.trim()
                    if (text.isNotEmpty()) {
                        synchronized(tail) { tail.addLast(text); if (tail.size > 40) tail.removeFirst() }
                        progress(text)
                    }
                }
            }
        }
        try {
            val exited = withTimeoutOrNull(timeout) { process.onExit().await() }
            if (exited == null) {
                end(process)
                error("measure.sh $command did not finish in ${timeout.inWholeMinutes} minutes. Its output is in $log")
            }
            reader.join()
            check(process.exitValue() == 0) { synchronized(tail) { tail.lastOrNull() } ?: "measure.sh $command failed. Its output is in $log" }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { end(process) }
            throw cancelled
        } finally {
            Live.remove(process)
        }
    }

    companion object {
        const val ApiUrl = "http://127.0.0.1:47180"
        const val DashboardUrl = "http://127.0.0.1:47130"
        const val IngestUrl = "http://127.0.0.1:47185"

        private val Decoder = Json { ignoreUnknownKeys = true }
        private val Live = ConcurrentHashMap<Process, Unit>()
        private val Probe by lazy {
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build()
        }

        init {
            Runtime.getRuntime().addShutdownHook(Thread { Live.keys.forEach(::end) })
        }

        fun find(workspace: Path): MeasureLocalServer? {
            val explicit = System.getenv("REAKTOR_ECOSYSTEM_ROOT")?.let(Path::of)
            val ancestors = generateSequence(workspace.toAbsolutePath().normalize()) { it.parent }.toList()
            return (listOfNotNull(explicit) + ancestors.flatMap { listOf(it.resolve("ecosystem"), it.resolve("reaktor/ecosystem")) })
                .firstOrNull { Files.isRegularFile(it.resolve("measure.sh")) }?.let(::MeasureLocalServer)
        }

        private fun answers(url: String): Boolean = runCatching {
            Probe.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200
        }.getOrDefault(false)

        private fun dockerAnswers(): Boolean = listOf("docker", "/usr/local/bin/docker", "/opt/homebrew/bin/docker").firstNotNullOfOrNull { executable ->
            runCatching {
                ProcessBuilder(executable, "info", "--format", "{{.ServerVersion}}").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            }.getOrNull()
        }?.let { docker ->
            val finished = docker.waitFor(10, TimeUnit.SECONDS)
            if (!finished) docker.destroyForcibly()
            finished && docker.exitValue() == 0
        } ?: false

        private fun end(process: Process) {
            val tree = process.descendants().toList()
            tree.forEach { it.destroy() }
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                tree.forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }
    }
}
