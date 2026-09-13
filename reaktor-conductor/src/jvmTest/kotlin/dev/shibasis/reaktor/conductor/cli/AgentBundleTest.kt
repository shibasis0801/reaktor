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

            val targets = AgentBundle.targets(root, home)
            AgentBundle.install(targets, command)
            val installed = codex.readText()
            assertTrue(installed.contains("[mcp_servers.reaktor]"))
            assertTrue(installed.contains("\"--dir\", \"/tmp/ws\""))
            assertTrue(AgentBundle.status(targets).codex)
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
            assertEquals(setOf("pencil", "reaktor"), servers.keys)
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
            val once = java.io.File(home, ".codex/config.toml").readText()
            val second = AgentBundle.install(targets, command)
            assertEquals(once, java.io.File(home, ".codex/config.toml").readText())
            assertTrue(second.any { it.contains("already installed") })
        } finally { home.deleteRecursively(); root.deleteRecursively() }
    }

    @Test fun aMissingCodexInstallationIsSkippedRatherThanCreated() {
        val home = Files.createTempDirectory("bundle-home").toFile()
        val root = Files.createTempDirectory("bundle-root").toFile()
        try {
            val targets = AgentBundle.targets(root, home)
            // Writing a config for a harness that is not installed would be presumptuous.
            assertTrue(AgentBundle.install(targets, command).any { it.contains("skipped") })
            assertFalse(java.io.File(home, ".codex/config.toml").exists())
        } finally { home.deleteRecursively(); root.deleteRecursively() }
    }
}
