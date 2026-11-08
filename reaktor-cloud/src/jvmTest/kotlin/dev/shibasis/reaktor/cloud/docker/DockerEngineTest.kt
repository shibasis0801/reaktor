package dev.shibasis.reaktor.cloud.docker

import dev.shibasis.dependeasy.files.deleteTreeSafely
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DockerEngineTest {
    private val containers = """[
        {"Id":"c1","Names":["/telemetry-store"],"State":"running","Labels":{"com.docker.compose.project":"telemetry"},
         "Ports":[{"IP":"0.0.0.0","PrivatePort":9000,"PublicPort":19000,"Type":"tcp"},{"IP":"::","PrivatePort":9000,"PublicPort":19000,"Type":"tcp"},
                  {"PrivatePort":53,"PublicPort":5353,"Type":"udp"}]},
        {"Id":"c2","Names":["/scratch"],"State":"exited","Labels":{},"Ports":[]}
    ]"""

    private fun daemon(answer: (String) -> Pair<Int, String>, check: (DockerEngine, List<String>) -> Unit) {
        val folder = Files.createTempDirectory("docker-engine").toFile()
        val socket = File(folder, "docker.sock")
        val seen = mutableListOf<String>()
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket.path))
        val worker = thread(isDaemon = true) {
            runCatching {
                while (true) server.accept().use { channel ->
                    val buffer = ByteBuffer.allocate(65_536)
                    while (!String(buffer.array(), 0, buffer.position()).contains("\r\n\r\n")) channel.read(buffer)
                    val head = String(buffer.array(), 0, buffer.position()).substringBefore("\r\n")
                    synchronized(seen) { seen += head }
                    val (status, body) = answer(head)
                    channel.write(ByteBuffer.wrap("HTTP/1.0 $status OK\r\nContent-Type: application/json\r\n\r\n$body".toByteArray()))
                }
            }
        }
        try {
            check(DockerEngine(DockerContext("fixture", DockerEndpoint.Socket(socket.path))), seen)
        } finally {
            server.close()
            worker.join(2_000)
            folder.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }

    @Test fun readsContainersAndTheirPublishedPorts() = daemon({ head ->
        if (head.startsWith("GET /containers/json")) 200 to containers else 404 to """{"message":"no such thing"}"""
    }) { docker, _ ->
        assertEquals(listOf("c1", "c2"), docker.containers().map { it["Id"].toString().trim('"') })
        assertEquals(listOf(19000), docker.publishedPorts())
        assertEquals(setOf("telemetry"), docker.composeProjects().keys)
        assertNull(docker.container("missing"))
    }

    @Test fun stopsAContainerThroughAPlan() = daemon({ head ->
        when {
            head.startsWith("GET /containers/scratch/json") -> 200 to """{"Id":"c2","State":{"Status":"running"}}"""
            head.startsWith("POST /containers/scratch/stop") -> 204 to ""
            else -> 404 to "{}"
        }
    }) { docker, seen ->
        val plan = docker.planStop("scratch")
        assertEquals("docker:fixture:container:scratch", plan.target)
        assertEquals(listOf("state"), plan.changes.map { it.path })
        val receipt = docker.apply(plan, plan.digest)
        assertEquals(204, receipt.status)
        assertEquals("c2", receipt.remoteVersion)
        assertEquals(listOf("GET /containers/scratch/json HTTP/1.0", "POST /containers/scratch/stop?t=10 HTTP/1.0", "GET /containers/scratch/json HTTP/1.0"), seen)
    }

    @Test fun readsTheCliContexts() {
        val home = Files.createTempDirectory("docker-home").toFile()
        try {
            File(home, ".docker/contexts/meta/a1").mkdirs()
            File(home, ".docker/contexts/meta/a1/meta.json").writeText("""{"Name":"m1-worker","Endpoints":{"docker":{"Host":"ssh://m1-worker"}}}""")
            File(home, ".docker/config.json").writeText("""{"currentContext":"m1-worker"}""")
            assertEquals(DockerContext("m1-worker", DockerEndpoint.Ssh("m1-worker")), DockerContexts.current(home) { null })
            assertEquals("default", DockerContexts.current(File(home, "elsewhere")) { null }.name)
        } finally {
            home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }
}
