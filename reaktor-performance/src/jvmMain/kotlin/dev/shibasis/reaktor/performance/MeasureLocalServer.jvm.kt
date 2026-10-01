package dev.shibasis.reaktor.performance

import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

class MeasureLocalServer private constructor(val ecosystem: Path) {
    fun start() = run("up", 25)

    fun stop() = run("stop", 3)

    fun connection(): MeasureLocalConnection {
        run("connect", 2)
        return Json { ignoreUnknownKeys = true }.decodeFromString<MeasureLocalConnection>(
            Files.readString(ecosystem.resolve(".state/connection.json")))
    }

    private fun run(command: String, timeoutMinutes: Long) {
        val log = ecosystem.resolve(".state/reaktor-startup.log")
        Files.createDirectories(log.parent)
        val process = ProcessBuilder("bash", ecosystem.resolve("measure.sh").toString(), command)
            .directory(ecosystem.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroy()
            error("Measure startup timed out. See $log")
        }
        check(process.exitValue() == 0) { "Measure startup failed. See $log" }
    }

    companion object {
        const val ApiUrl = "http://127.0.0.1:47180"
        const val DashboardUrl = "http://127.0.0.1:47130"
        const val IngestUrl = "http://127.0.0.1:47185"

        fun find(workspace: Path): MeasureLocalServer? {
            val explicit = System.getenv("REAKTOR_ECOSYSTEM_ROOT")?.let(Path::of)
            val ancestors = generateSequence(workspace.toAbsolutePath().normalize()) { it.parent }.toList()
            return (listOfNotNull(explicit) + ancestors.flatMap { listOf(it.resolve("ecosystem"), it.resolve("reaktor/ecosystem")) })
                .firstOrNull { Files.isRegularFile(it.resolve("measure.sh")) }?.let(::MeasureLocalServer)
        }

        fun ready(): Boolean = runCatching {
            val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build()
            listOf(ApiUrl + "/ping", IngestUrl + "/ping", DashboardUrl).all { url ->
                client.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200
            }
        }.getOrDefault(false)
    }
}
