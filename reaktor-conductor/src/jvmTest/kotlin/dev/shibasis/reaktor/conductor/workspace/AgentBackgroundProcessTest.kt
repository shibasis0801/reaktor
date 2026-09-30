package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.test.*

class AgentBackgroundProcessTest {
    @Test fun clientsDetachAndLaunchdRestartsACrashedOwner() = runBlocking {
        assumeTrue(System.getenv("REAKTOR_AGENT_SERVICE_TEST") == "1")
        val root = Files.createTempDirectory("reaktor-background-process").toFile()
        val data = AgentWorkspaceConnection.defaultDirectory(root)
        val command = listOf(File(System.getProperty("java.home"), "bin/java").path, "-cp",
            System.getProperty("reaktor.conductor.testClasspath"), "dev.shibasis.reaktor.conductor.workspace.AgentBackgroundProcessTestKt", root.path)
        try {
            val first = AgentBackgroundService.connect(root, command)
            val pid = first.info().service!!.processId
            val run = first.submit(AgentSubmission("detach", RuntimeKind.Echo, "work while detached"))
            first.close()
            AgentWorkspaceConnection.open(root, allowStart = false).use { client ->
                withTimeout(15000) { while (client.get(run.id).status == AgentRunStatus.Running) delay(100) }
                assertEquals(AgentRunStatus.Completed, client.get(run.id).status)
                assertEquals(pid, client.info().service!!.processId)
                val crashed = client.submit(AgentSubmission("crash", RuntimeKind.Echo, "recover after crash"))
                withTimeout(10000) {
                    while (client.call("agent_activity", buildJsonObject { put("runId", crashed.id) }).jsonObject.getValue("records").jsonArray.isEmpty()) delay(30)
                }
                ProcessHandle.of(pid).orElseThrow().destroyForcibly()
                withTimeout(30000) {
                    while (runCatching { client.info().service!!.processId != pid }.getOrDefault(false).not()) delay(200)
                }
                withTimeout(10000) { while (client.get(crashed.id).recovery == AgentRecovery.Pending) delay(50) }
                assertEquals(AgentRecovery.NeedsReview, client.get(crashed.id).recovery)
                client.call("agent_resume", buildJsonObject { put("runId", crashed.id) })
                withTimeout(15000) { while (client.get(crashed.id).status == AgentRunStatus.Running) delay(100) }
                assertEquals(AgentRunStatus.Completed, client.get(crashed.id).status)
                assertEquals(2, client.get(crashed.id).attempt)
            }
        } finally {
            runCatching { AgentBackgroundService.stop(root) }
            Files.deleteIfExists(java.nio.file.Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", "${AgentBackgroundService.label(root)}.plist"))
            root.deleteRecursively(); data.toFile().deleteRecursively()
        }
    }
}

fun main(args: Array<String>) {
    val runtime = object : AgentRuntime {
        override val kind = RuntimeKind.Echo
        override fun run(request: AgentRequest) = flow<AgentEvent> {
            emit(AgentEvent.Activity(request.agent.id, AgentActivityItem("fixture", ActivityKind.Tool, "Fixture operation", ActivityStatus.Started)))
            delay(1800)
            emit(AgentEvent.Activity(request.agent.id, AgentActivityItem("fixture", ActivityKind.Tool, "Fixture operation", ActivityStatus.Completed, output = "verified")))
            emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "Completed fixture", true)))
        }
    }
    val owner = AgentWorkspaceConnection.open(File(args.single()), runtimes = mapOf(runtime.kind to runtime), background = true, discover = { ProviderCapability(it) })
    Runtime.getRuntime().addShutdownHook(Thread { owner.close() })
    CountDownLatch(1).await()
}
