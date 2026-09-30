package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AgentOperationsTest {
    @Test fun compactedActivityKeepsEveryCursorAndSupportsLaterAppends() {
        val data = Files.createTempDirectory("activity-archive")
        try {
            val id = "a".repeat(64)
            val store = AgentActivityStore(data)
            val original = (1..8).map { store.append(id, "worker", 1, AgentActivityItem("item$it", ActivityKind.Tool, "Tool", output = "text".repeat(1000))) }
            assertTrue(store.archive(id) > 0)
            assertEquals(original, store.page(id, limit = 100).records)
            val reopened = AgentActivityStore(data)
            assertEquals(9, reopened.append(id, "worker", 2, AgentActivityItem("last", ActivityKind.Recovery, "Recorded")).sequence)
            assertEquals(original.drop(4), reopened.page(id, after = 4, limit = 4).records)
            reopened.archive(id)
            assertEquals(9, AgentActivityStore(data).page(id, limit = 100).records.size)
        } finally { data.toFile().deleteRecursively() }
    }
    @Test fun supervisorConfigurationsAndRemoteArgvPreserveLiteralArguments() {
        val data = Files.createTempDirectory("supervisor-config")
        try {
            val unit = PlatformAgentService.systemdConfiguration(listOf("/jdk/bin/java", "-cp", "/a b/c.jar", "Main", "100%", "a\$b"), data.toFile(), data)
            assertTrue(unit.contains("Restart=always")); assertTrue(unit.contains("KillMode=control-group"))
            assertTrue(unit.contains("100%%")); assertTrue(unit.contains("a\$\$b"))
            val task = PlatformAgentService.windowsConfiguration("reaktor-test", listOf("C:\\Java\\java.exe", "it's literal"), data.toFile(), data)
            assertTrue(task.contains("-LogonType Interactive")); assertTrue(task.contains("-ExecutionTimeLimit ([TimeSpan]::Zero)"))
            assertTrue(Files.readString(data.resolve("run-service.ps1")).contains("it''s literal"))
            val remote = AgentRemoteWorkspace(AgentRemoteProfile("dev-host", "/work/it's literal", "/opt/agent workspace"))
            assertEquals("dev-host", remote.command()[remote.command().lastIndex - 1])
            assertTrue(remote.command().last().contains("'\"'\"'"))
            assertFailsWith<IllegalArgumentException> { AgentRemoteWorkspace(AgentRemoteProfile("-oProxyCommand=bad", "/work", "/tool")) }
        } finally { data.toFile().deleteRecursively() }
    }
    @Test fun localContextCannotReadAnExportFromAnotherScope() {
        val data = Files.createTempDirectory("context-scope")
        try {
            val export = data.resolve("export.json")
            Files.writeString(export, "{\"tenantId\":\"other\",\"workspaceId\":\"workspace\",\"principalId\":\"operator\"}")
            atomicWrite(data.resolve("local-context.json"), ConductorJson.encodeToString(AgentLocalContextConfig.serializer(),
                AgentLocalContextConfig(data.toString(), "tenant", "workspace", "operator", "/bin/echo", export.toString(), data.toString())))
            val local = AgentLocalContext(data.toFile(), data)
            assertFailsWith<IllegalArgumentException> { local.search("query", false) }
        } finally { data.toFile().deleteRecursively() }
    }
}
