package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.workspace.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class NativeActivityLiveTest {
    @Test fun codexToolsReachTheDurableActivityFeed() = probe(RuntimeKind.Codex)
    @Test fun claudeToolsReachTheDurableActivityFeed() = probe(RuntimeKind.ClaudeCode)

    private fun probe(provider: RuntimeKind) = runBlocking(Dispatchers.IO) {
        assumeTrue(System.getenv("REAKTOR_NATIVE_ACTIVITY_TEST") == "1")
        val root = Files.createTempDirectory("native-activity").toFile()
        val data = Files.createTempDirectory("native-activity-data")
        File(root, "probe.txt").writeText("LOCAL_ACTIVITY_VERIFIED")
        try {
            AgentWorkspaceConnection.open(root, data).use { owner ->
                var run = owner.submit(AgentSubmission("activity-probe", provider,
                    "Read probe.txt using a file read or shell tool. Reply with the exact contents. Do not edit anything.",
                    effort = NativeEffort("low")))
                withTimeout(180000) {
                    while (run.status == AgentRunStatus.Running) {
                        check(run.pending.isEmpty()) { "Read probe unexpectedly needs approval" }
                        run = owner.wait(run.id, run.revision, 10000)
                    }
                }
                assertEquals(AgentRunStatus.Completed, run.status, run.failure)
                assertTrue(run.output.contains("LOCAL_ACTIVITY_VERIFIED"))
                val page = AgentWorkspaceJson.decodeFromJsonElement(AgentActivityPage.serializer(),
                    owner.call("agent_activity", buildJsonObject { put("runId", run.id); put("limit", 100) }))
                val tools = page.records.filter { it.item.kind == ActivityKind.Tool }
                val started = tools.filter { it.item.status == ActivityStatus.Started }.map { it.item.id }.toSet()
                assertTrue(started.isNotEmpty(), "No native tool start reached the journal")
                assertTrue(tools.any { it.item.status == ActivityStatus.Completed && it.item.id in started }, "No matching native tool completion reached the journal")
            }
        } finally { root.deleteRecursively(); data.toFile().deleteRecursively() }
    }
}
