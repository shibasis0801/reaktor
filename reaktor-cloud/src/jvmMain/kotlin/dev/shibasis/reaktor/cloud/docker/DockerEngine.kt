package dev.shibasis.reaktor.cloud.docker

import dev.shibasis.reaktor.cloud.api.PlannedRequest
import dev.shibasis.reaktor.cloud.api.WritePlan
import dev.shibasis.reaktor.cloud.api.WritePlans
import dev.shibasis.reaktor.cloud.api.WriteReceipt
import dev.shibasis.reaktor.cloud.http.*
import dev.shibasis.reaktor.core.framework.lenientJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

sealed interface DockerEndpoint {
    data class Socket(val path: String) : DockerEndpoint
    data class Ssh(val destination: String, val docker: String = "docker") : DockerEndpoint

    companion object {
        fun parse(host: String): DockerEndpoint = when {
            host.startsWith("unix://") -> Socket(host.removePrefix("unix://"))
            host.startsWith("ssh://") -> Ssh(host.removePrefix("ssh://").trimEnd('/'))
            else -> throw IllegalArgumentException("Reaktor reaches Docker through a unix socket or ssh, not $host")
        }
    }
}

data class DockerContext(val name: String, val endpoint: DockerEndpoint)

object DockerContexts {
    fun read(home: File = File(System.getProperty("user.home"))): List<DockerContext> =
        File(home, ".docker/contexts/meta").listFiles().orEmpty().map { File(it, "meta.json") }.filter(File::isFile).mapNotNull { file ->
            val meta = lenientJson.parseToJsonElement(file.readText())
            val host = meta.obj("Endpoints").obj("docker").text("Host") ?: return@mapNotNull null
            DockerContext(meta.text("Name") ?: return@mapNotNull null, DockerEndpoint.parse(host))
        }.sortedBy { it.name }

    fun current(home: File = File(System.getProperty("user.home")), environment: (String) -> String? = System::getenv): DockerContext {
        val name = environment("DOCKER_CONTEXT")?.takeIf { it.isNotBlank() }
            ?: File(home, ".docker/config.json").takeIf(File::isFile)?.let { lenientJson.parseToJsonElement(it.readText()).text("currentContext") }
        return read(home).firstOrNull { it.name == name }
            ?: DockerContext("default", DockerEndpoint.Socket(environment("DOCKER_HOST")?.removePrefix("unix://") ?: "/var/run/docker.sock"))
    }
}

class DockerFailure(val status: Int, message: String) : Exception(message)

class DockerResponse(val status: Int, val headers: Map<String, String>, val body: String) {
    val json: JsonElement get() = runCatching { lenientJson.parseToJsonElement(body) }.getOrDefault(JsonNull)
}

class DockerEngine(val context: DockerContext, private val timeout: Duration = Duration.ofSeconds(60)) {
    fun info(): JsonObject = get("/info") as JsonObject
    fun version(): JsonObject = get("/version") as JsonObject
    fun containers(all: Boolean = true): List<JsonObject> = get("/containers/json?all=$all").list()
    fun container(id: String): JsonObject? = find("/containers/${encode(id)}/json")
    fun images(): List<JsonObject> = get("/images/json").list()
    fun image(name: String): JsonObject? = find("/images/${encode(name)}/json")

    fun publishedPorts(): List<Int> = containers(all = false).flatMap { it.objects("Ports") }
        .filter { it.text("Type") == "tcp" }.mapNotNull { it.long("PublicPort")?.toInt() }.distinct().sorted()

    fun composeProjects(): Map<String, List<JsonObject>> = containers()
        .groupBy { it.obj("Labels").text(ComposeProject) }.filterKeys { it != null }.mapKeys { it.key!! }

    fun planStart(container: String) = planState(container, "start", "running")
    fun planStop(container: String) = planState(container, "stop?t=10", "exited")
    fun planRestart(container: String) = planState(container, "restart?t=10", "running")

    fun planRemove(container: String): WritePlan {
        val live = container(container)
        return WritePlans.plan(target("container", container), PlannedRequest("DELETE", "/containers/${encode(container)}"), live?.let(::summary), null,
            liveVersion = live.text("Id"))
    }

    fun planPull(image: String): WritePlan {
        val live = image(image)
        val name = image.substringBeforeLast(':').takeIf { ':' in image.substringAfterLast('/') } ?: image
        val tag = image.substringAfterLast('/').substringAfterLast(':', "latest")
        return WritePlans.plan(target("image", image), PlannedRequest("POST", "/images/create?fromImage=${encode(name)}&tag=${encode(tag)}"),
            live?.let { JsonObject(mapOf("Id" to (it["Id"] ?: JsonNull))) }, JsonObject(mapOf("image" to JsonPrimitive(image))), liveVersion = live.text("Id"))
    }

    fun apply(plan: WritePlan, approved: String): WriteReceipt {
        WritePlans.confirm(plan, approved)
        val response = send(plan.request.method, plan.request.url, plan.request.body)
        if (response.status !in 200..299) throw DockerFailure(response.status, failure(plan.request.method, plan.request.url, response))
        val (kind, name) = plan.target.removePrefix("docker:${context.name}:").let { it.substringBefore(':') to it.substringAfter(':') }
        val after = if (kind == "image") image(name) else container(name)
        return WriteReceipt(plan.target, approved, response.status, after.text("Id"))
    }

    fun send(method: String, path: String, body: JsonElement? = null): DockerResponse = open().use { stream ->
        val payload = body?.toString()?.toByteArray()
        val head = buildString {
            append("$method $path HTTP/1.0\r\nHost: docker\r\nUser-Agent: reaktor-cloud\r\nAccept: application/json\r\n")
            if (payload != null) append("Content-Type: application/json\r\nContent-Length: ${payload.size}\r\n")
            append("\r\n")
        }
        stream.output.write(head.toByteArray())
        payload?.let(stream.output::write)
        stream.output.flush()
        val bytes = stream.input.readNBytes(MaxBytes + 1)
        check(bytes.size <= MaxBytes) { "Docker answered $method $path with more than ${MaxBytes / 1_048_576} MiB" }
        response(bytes, "$method $path")
    }

    private fun planState(container: String, action: String, state: String): WritePlan {
        val live = container(container)
        return WritePlans.plan(target("container", container), PlannedRequest("POST", "/containers/${encode(container)}/$action"),
            live?.let(::summary), JsonObject(mapOf("state" to JsonPrimitive(state))), liveVersion = live.text("Id"))
    }

    private fun summary(container: JsonObject) = JsonObject(mapOf("state" to (container.obj("State")["Status"] ?: JsonNull)))

    private fun target(kind: String, name: String) = "docker:${context.name}:$kind:$name"

    private fun get(path: String): JsonElement = send("GET", path).let { response ->
        if (response.status !in 200..299) throw DockerFailure(response.status, failure("GET", path, response))
        response.json
    }

    private fun find(path: String): JsonObject? = try { get(path) as? JsonObject } catch (missing: DockerFailure) {
        if (missing.status == 404) null else throw missing
    }

    private fun failure(method: String, path: String, response: DockerResponse) =
        "Docker on ${context.name} answered HTTP ${response.status} for $method $path: ${response.json.text("message").orEmpty()}".trim()

    private fun response(bytes: ByteArray, request: String): DockerResponse {
        val split = (0..bytes.size - 4).firstOrNull { bytes[it] == CR && bytes[it + 1] == LF && bytes[it + 2] == CR && bytes[it + 3] == LF }
            ?: throw DockerFailure(0, "Docker on ${context.name} closed $request without an answer")
        val lines = String(bytes, 0, split, Charsets.ISO_8859_1).split("\r\n")
        val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: throw DockerFailure(0, "Unreadable answer from Docker: ${lines.first()}")
        val headers = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        return DockerResponse(status, headers, String(bytes, split + 4, bytes.size - split - 4, Charsets.UTF_8))
    }

    private fun open(): Stream {
        val stream = when (val endpoint = context.endpoint) {
            is DockerEndpoint.Socket -> SocketChannel.open(UnixDomainSocketAddress.of(endpoint.path)).let { channel ->
                Stream(Channels.newInputStream(channel), Channels.newOutputStream(channel)) { channel.close() }
            }
            is DockerEndpoint.Ssh -> ProcessBuilder("/usr/bin/ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", "-T", "--",
                endpoint.destination, endpoint.docker, "system", "dial-stdio").redirectError(ProcessBuilder.Redirect.DISCARD).start().let { process ->
                Stream(process.inputStream, process.outputStream) { process.destroy() }
            }
        }
        thread(isDaemon = true, name = "docker-${context.name}-deadline") {
            if (!stream.done.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) stream.close()
        }
        return stream
    }

    private class Stream(val input: InputStream, val output: OutputStream, private val release: () -> Unit) : Closeable {
        val done = CountDownLatch(1)
        override fun close() {
            done.countDown()
            runCatching { release() }
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        const val ComposeProject = "com.docker.compose.project"
        private const val MaxBytes = 16_777_216
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()
    }
}
