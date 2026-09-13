package dev.shibasis.reaktor.conductor.cli

import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

/**
 * Installing into someone else's configuration.
 *
 * The property under test is not "the entry appears" — it is that everything else survives. These
 * files hold the operator's own servers, credentials and settings, and a bundle that reformats or
 * drops one of them while adding its own has done more damage than it was worth.
 */
class AgentBundleTest {
    private val command = listOf("/usr/bin/java", "-cp", "/tmp/x.jar", "Main", "workspace", "mcp", "--dir", "/tmp/ws")

    @Test fun uninstallReturnsTheOperatorsConfigurationExactlyAsItWas() {
        val home = Files.createTempDirectory("bundle-home").toFile()
        val root = Files.createTempDirectory("bundle-root").toFile()
        try {
            val codex = java.io.File(home, ".codex/config.toml").apply { parentFile.mkdirs() }
            // CAPTURED shape: a real config with neighbours on both sides, including a nested table.
            val original = """
                model = "gpt-6-astra"
                model_reasoning_effort = "xhigh"

                [mcp_servers.manna]
                url = "https://manna.ac/api/mcp"

                [mcp_servers.manna.http_headers]
                Authorization = "Bearer secret-token"

                [mcp_servers.node_repl]
                args = []
                command = "/node_repl"
            """.trimIndent() + "\n"
            codex.writeText(original)

            val targets = AgentBundle.Targets(codex, java.io.File(root, ".mcp.json"))
            AgentBundle.install(targets, command)
            val installed = codex.readText()
            assertTrue(installed.contains("[mcp_servers.reaktor]"))
            assertTrue(installed.contains("\"--dir\", \"/tmp/ws\""))
            // Both servers: the workspace over stdio, the kernel's graph over loopback http.
            assertTrue(installed.contains("[mcp_servers.reaktor-graph]"))
            assertFalse(installed.contains("--graph-url"), "Default graph registration follows workspace discovery across restarts")
            val status = AgentBundle.status(targets)
            assertTrue(status.codex)
            assertTrue(status.codexGraph)
            // Neighbours are untouched while ours is present.
            assertTrue(installed.contains("Authorization = \"Bearer secret-token\""))

            AgentBundle.uninstall(targets)
            assertEquals(original, codex.readText(), "Uninstall has to give the file back byte for byte")
            assertFalse(AgentBundle.status(targets).codex)
        } finally { home.deleteRecursively(); root.deleteRecursively() }
    }

    @Test fun aClaudeProjectFileKeepsItsOtherServersAndLosesOnlyOurs() {
        val home = Files.createTempDirectory("bundle-home").toFile()
        val root = Files.createTempDirectory("bundle-root").toFile()
        try {
            val mcp = java.io.File(root, ".mcp.json")
            mcp.writeText("""{"mcpServers":{"pencil":{"command":"pencil-mcp","args":[]}}}""")
            val targets = AgentBundle.targets(root, home)

            AgentBundle.install(targets, command)
            val servers = Json.parseToJsonElement(mcp.readText()).jsonObject.getValue("mcpServers").jsonObject
            assertEquals(setOf("pencil", "reaktor", "reaktor-graph"), servers.keys)
            assertTrue(servers.getValue("reaktor-graph").jsonObject.getValue("args").jsonArray.any { it.jsonPrimitive.content == "graph-mcp" })
            assertEquals("pencil-mcp", servers.getValue("pencil").jsonObject.getValue("command").jsonPrimitive.content)

            AgentBundle.uninstall(targets)
            val after = Json.parseToJsonElement(mcp.readText()).jsonObject.getValue("mcpServers").jsonObject
            assertEquals(setOf("pencil"), after.keys, "Someone else's server is not ours to remove")
        } finally { home.deleteRecursively(); root.deleteRecursively() }
    }

    @Test fun aFileThisBundleCreatedIsRemovedRatherThanLeftEmpty() {
        val home = Files.createTempDirectory("bundle-home").toFile()
        val root = Files.createTempDirectory("bundle-root").toFile()
        try {
            val targets = AgentBundle.targets(root, home)
            AgentBundle.install(targets, command)
            assertTrue(java.io.File(root, ".mcp.json").isFile)
            AgentBundle.uninstall(targets)
            assertFalse(java.io.File(root, ".mcp.json").exists(), "An empty file we created is litter")
        } finally { home.deleteRecursively(); root.deleteRecursively() }
    }

    @Test fun installingTwiceDoesNotDuplicateTheEntry() {
        val home = Files.createTempDirectory("bundle-home").toFile()
        val root = Files.createTempDirectory("bundle-root").toFile()
        try {
            java.io.File(home, ".codex/config.toml").apply { parentFile.mkdirs() }.writeText("model = \"x\"\n")
            val targets = AgentBundle.targets(root, home)
            AgentBundle.install(targets, command)
            val once = targets.codexConfig.readText()
            val second = AgentBundle.install(targets, command)
            assertEquals(once, targets.codexConfig.readText())
            // Two servers across two harnesses: every one of them reports itself already there
            // rather than appending a second copy.
            assertEquals(second.size, second.count { it.contains("already installed") })
        } finally { home.deleteRecursively(); root.deleteRecursively() }
    }

    @Test fun installationCreatesOnlyProjectConfiguration() {
        val home = Files.createTempDirectory("bundle-home").toFile()
        val root = Files.createTempDirectory("bundle-root").toFile()
        try {
            val targets = AgentBundle.targets(root, home)
            // Writing a config for a harness that is not installed would be presumptuous.
            AgentBundle.install(targets, command)
            assertTrue(targets.codexConfig.exists())
            assertFalse(java.io.File(home, ".codex/config.toml").exists())
        } finally { home.deleteRecursively(); root.deleteRecursively() }
    }
    @Test fun twoProjectsRemainBoundAndUnownedEntriesSurvive() {
        val home = Files.createTempDirectory("bundle-home").toFile()
        val root = Files.createTempDirectory("bundle-pair").toFile()
        try {
            val a = java.io.File(root, "a").apply { mkdirs() }
            val b = java.io.File(root, "b").apply { mkdirs() }
            val ta = AgentBundle.targets(a, home)
            val tb = AgentBundle.targets(b, home)
            tb.codexConfig.parentFile.mkdirs()
            val original = "[mcp_servers.reaktor]\ncommand = \"operator-owned\"\n"
            tb.codexConfig.writeText(original)
            tb.claudeProjectConfig.writeText("""{"mcpServers":{"reaktor":{"command":"operator-owned"}},"setting":true}""")
            AgentBundle.install(ta, AgentBundle.launchCommand(a, listOf("java")))
            AgentBundle.install(tb, AgentBundle.launchCommand(b, listOf("java")))
            assertTrue(ta.codexConfig.readText().contains(a.canonicalPath))
            assertTrue(tb.codexConfig.readText().contains(b.canonicalPath))
            assertFalse(ta.codexConfig.readText().contains(b.canonicalPath))
            AgentBundle.uninstall(tb)
            assertEquals(original, tb.codexConfig.readText())
            assertEquals("operator-owned", Json.parseToJsonElement(tb.claudeProjectConfig.readText()).jsonObject
                .getValue("mcpServers").jsonObject.getValue("reaktor").jsonObject.getValue("command").jsonPrimitive.content)
        } finally { home.deleteRecursively(); root.deleteRecursively() }
    }

    @Test fun malformedClaudeConfigDoesNotGetReplacedOrPartiallyInstallCodex() {
        val root = Files.createTempDirectory("bundle-invalid").toFile()
        try {
            val targets = AgentBundle.targets(root)
            targets.claudeProjectConfig.writeText("{invalid")
            assertFails { AgentBundle.install(targets, command) }
            assertEquals("{invalid", targets.claudeProjectConfig.readText())
            assertFalse(targets.codexConfig.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun modifiedOwnedEntrySurvivesUninstall() {
        val root = Files.createTempDirectory("bundle-modified").toFile()
        try {
            val targets = AgentBundle.targets(root)
            AgentBundle.install(targets, command)
            targets.codexConfig.appendText("[mcp_servers.reaktor.env]\nUSER_SETTING = \"preserve\"\n")
            AgentBundle.uninstall(targets)
            assertTrue(targets.codexConfig.readText().contains("USER_SETTING"))
            assertTrue(targets.codexConfig.readText().contains("[mcp_servers.reaktor]"))
        } finally { root.deleteRecursively() }
    }
}
