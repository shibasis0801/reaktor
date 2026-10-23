package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely

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

            val targets = AgentBundle.Targets(codex, java.io.File(root, ".mcp.json"), java.io.File(home, ".gemini/config/mcp_config.json"), java.io.File(home, ".local/bin/reaktor-agent"))
            AgentBundle.install(targets, command)
            val installed = codex.readText()
            assertTrue(installed.contains("[mcp_servers.reaktor]"))
            assertTrue(installed.contains("\"--dir\", \"/tmp/ws\""))
            // One server: the kernel is a provider behind it, not a second entry to keep in step.
            assertFalse(installed.contains("[mcp_servers.reaktor-graph]"))
            assertTrue(installed.contains("\"--seat\", \"codex\""), "each harness is seated so its calls can be attributed")
            val status = AgentBundle.status(targets)
            assertTrue(status.codex)
            assertFalse(status.codexGraph)
            // Neighbours are untouched while ours is present.
            assertTrue(installed.contains("Authorization = \"Bearer secret-token\""))

            AgentBundle.uninstall(targets)
            assertEquals(original, codex.readText(), "Uninstall has to give the file back byte for byte")
            assertFalse(AgentBundle.status(targets).codex)
        } finally { home.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
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
            assertEquals(listOf("--seat", "claude-code"), servers.getValue("reaktor").jsonObject.getValue("args").jsonArray.map { it.jsonPrimitive.content }.takeLast(2))
            assertEquals("pencil-mcp", servers.getValue("pencil").jsonObject.getValue("command").jsonPrimitive.content)

            AgentBundle.uninstall(targets)
            val after = Json.parseToJsonElement(mcp.readText()).jsonObject.getValue("mcpServers").jsonObject
            assertEquals(setOf("pencil"), after.keys, "Someone else's server is not ours to remove")
        } finally { home.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun theHangarSkillReachesEveryVendorAndAnEditedCopyIsLeftAlone() {
        val home = Files.createTempDirectory("bundle-home").toFile()
        val root = Files.createTempDirectory("bundle-root").toFile()
        try {
            val targets = AgentBundle.targets(root, home)
            val messages = AgentBundle.install(targets, command)
            val claude = java.io.File(home, ".claude/skills/hangar/SKILL.md")
            val codex = java.io.File(home, ".agents/skills/hangar/SKILL.md")
            val antigravity = java.io.File(home, ".gemini/config/skills/hangar/SKILL.md")
            assertTrue(listOf(claude, codex, antigravity, java.io.File(home, ".agents/skills/hangar/agents/openai.yaml")).all { it.isFile })
            assertTrue("claude:${'$'}{CLAUDE_SESSION_ID}" in claude.readText())
            assertTrue("disable-model-invocation: true" in claude.readText())
            assertTrue("${'$'}hangar" in codex.readText() && "CLAUDE_SESSION_ID" !in codex.readText())
            assertTrue("taskgraph.propose" in antigravity.readText() && "\nname: hangar\n" in antigravity.readText())
            assertEquals(4, messages.count { it.startsWith("skill/") && "installed" in it })
            assertTrue(AgentBundle.status(targets).skills)

            assertTrue(AgentBundle.install(targets, command).filter { it.startsWith("skill/") }.all { it.endsWith("already installed") })
            codex.writeText(codex.readText() + "\nMy own note.\n")
            assertFalse(AgentBundle.status(targets).skills)
            assertTrue(AgentBundle.install(targets, command).any { it.startsWith("skill/codex:") && "collision" in it })
            AgentBundle.uninstall(targets)
            assertFalse(claude.exists())
            assertFalse(antigravity.exists())
            assertTrue(codex.readText().endsWith("My own note.\n"), "An edited skill is the operator's now")
        } finally { home.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
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
        } finally { home.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
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
        } finally { home.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
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
        } finally { home.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
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
            AgentBundle.install(ta, listOf("java", "workspace", "mcp", "--dir", a.canonicalPath))
            val collided = AgentBundle.install(tb, listOf("java", "workspace", "mcp", "--dir", b.canonicalPath))
            assertTrue(ta.codexConfig.readText().contains(a.canonicalPath))
            // A name the operator already owns stays exactly theirs.
            assertTrue(tb.codexConfig.readText().endsWith(original))
            assertTrue(collided.any { it.startsWith("codex/reaktor: collision") }, collided.toString())
            assertFalse(ta.codexConfig.readText().contains(b.canonicalPath))
            AgentBundle.uninstall(tb)
            assertEquals(original, tb.codexConfig.readText())
            assertEquals("operator-owned", Json.parseToJsonElement(tb.claudeProjectConfig.readText()).jsonObject
                .getValue("mcpServers").jsonObject.getValue("reaktor").jsonObject.getValue("command").jsonPrimitive.content)
        } finally { home.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun malformedClaudeConfigDoesNotGetReplacedOrPartiallyInstallCodex() {
        val root = Files.createTempDirectory("bundle-invalid").toFile()
        try {
            val targets = AgentBundle.targets(root)
            targets.claudeProjectConfig.writeText("{invalid")
            assertFails { AgentBundle.install(targets, command) }
            assertEquals("{invalid", targets.claudeProjectConfig.readText())
            assertFalse(targets.codexConfig.exists())
        } finally { root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun modifiedOwnedEntrySurvivesUninstall() {
        val home = Files.createTempDirectory("bundle-home").toFile()
        val root = Files.createTempDirectory("bundle-modified").toFile()
        try {
            val targets = AgentBundle.targets(root, home)
            AgentBundle.install(targets, command)
            targets.codexConfig.appendText("[mcp_servers.reaktor.env]\nUSER_SETTING = \"preserve\"\n")
            AgentBundle.uninstall(targets)
            assertTrue(targets.codexConfig.readText().contains("USER_SETTING"))
            assertTrue(targets.codexConfig.readText().contains("[mcp_servers.reaktor]"))
        } finally { home.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun antigravityIsRegisteredWhereAgyReadsItAndFollowsTheWorkspaceItIsStartedIn() {
        val home = Files.createTempDirectory("bundle-home").toFile()
        val root = Files.createTempDirectory("bundle-root").toFile()
        try {
            // CAPTURED shape: `agy` keeps one file per user, and someone else's server is in it.
            val config = java.io.File(home, ".gemini/config/mcp_config.json").apply { parentFile.mkdirs() }
            config.writeText("""{"mcpServers":{"pencil":{"command":"pencil-mcp","args":[],"env":{}}}}""")
            val targets = AgentBundle.targets(root, home)
            assertEquals(config, targets.antigravityConfig, "agy reads ~/.gemini/config/mcp_config.json, not a project file")

            val installed = AgentBundle.install(targets, command)
            // A registered server the harness will refuse to call is not a working install, so the
            // operator is told the exact rule rather than left to discover the denial mid-turn.
            assertTrue(installed.any { it.contains("mcp(reaktor/*)") && it.contains("permissions.allow") }, installed.toString())
            val servers = Json.parseToJsonElement(config.readText()).jsonObject.getValue("mcpServers").jsonObject
            assertEquals(setOf("pencil", "reaktor"), servers.keys)
            assertEquals("pencil-mcp", servers.getValue("pencil").jsonObject.getValue("command").jsonPrimitive.content)
            // One file serves every project, so this entry names no workspace: it resolves the
            // directory agy was started in. The per-project harnesses keep their pinned --dir.
            val args = servers.getValue("reaktor").jsonObject.getValue("args").jsonArray.map { it.jsonPrimitive.content }
            assertFalse("--dir" in args, "A machine-wide entry that pins one workspace would serve the wrong project")
            assertEquals(listOf("workspace", "mcp", "--seat", "gemini"), args.takeLast(4))
            assertTrue("--dir" in Json.parseToJsonElement(targets.claudeProjectConfig.readText()).jsonObject
                .getValue("mcpServers").jsonObject.getValue("reaktor").jsonObject.getValue("args").jsonArray.map { it.jsonPrimitive.content })
            assertTrue(AgentBundle.status(targets).antigravity)
            assertFalse(AgentBundle.status(targets).antigravityGraph)

            AgentBundle.uninstall(targets)
            val after = Json.parseToJsonElement(config.readText()).jsonObject.getValue("mcpServers").jsonObject
            assertEquals(setOf("pencil"), after.keys, "Someone else's server is not ours to remove")
            assertEquals("{}", after.getValue("pencil").jsonObject.getValue("env").toString(), "Their entry is returned as it was")
            assertFalse(AgentBundle.status(targets).antigravity)
        } finally { home.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun aSharedAntigravityFileWeDidNotCreateSurvivesUninstall() {
        val home = Files.createTempDirectory("bundle-home").toFile()
        val root = Files.createTempDirectory("bundle-root").toFile()
        try {
            val targets = AgentBundle.targets(root, home)
            AgentBundle.install(targets, command)
            assertTrue(targets.antigravityConfig.isFile)
            AgentBundle.uninstall(targets)
            assertFalse(targets.antigravityConfig.exists(), "An empty file we created is litter")

            targets.antigravityConfig.parentFile.mkdirs()
            targets.antigravityConfig.writeText("""{"mcpServers":{}}""")
            AgentBundle.install(targets, command)
            AgentBundle.uninstall(targets)
            assertTrue(targets.antigravityConfig.isFile, "A user-owned file is emptied of our entries, never deleted")
        } finally { home.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun anEarlierTwoServerInstallIsRetiredToOneAndAGraphEntrySomeoneEditedIsLeftAlone() {
        val home = Files.createTempDirectory("bundle-home").toFile()
        val root = Files.createTempDirectory("bundle-root").toFile()
        try {
            val targets = AgentBundle.targets(root, home)
            val graph = buildJsonObject { put("command", "java"); put("args", JsonArray(listOf("workspace", "graph-mcp").map(::JsonPrimitive))) }
            val chunk = "\n# reaktor-bundle begin reaktor-graph\n[mcp_servers.reaktor-graph]\ncommand = \"java\"\nargs = [\"workspace\", \"graph-mcp\"]\n# reaktor-bundle end reaktor-graph\n"
            targets.codexConfig.apply { parentFile.mkdirs() }.writeText(chunk)
            targets.claudeProjectConfig.writeText(buildJsonObject { putJsonObject("mcpServers") { put("reaktor-graph", graph); put("pencil", buildJsonObject { put("command", "pencil-mcp") }) } }.toString())
            // Antigravity's copy was edited by its owner after we wrote it.
            targets.antigravityConfig.apply { parentFile.mkdirs() }.writeText(buildJsonObject { putJsonObject("mcpServers") { put("reaktor-graph", buildJsonObject { put("command", "edited") }) } }.toString())
            targets.manifest.apply { parentFile.mkdirs() }.writeText(buildJsonObject { putJsonObject("entries") {
                put("codex/reaktor-graph", chunk); put("claude/reaktor-graph", graph); put("antigravity/reaktor-graph", graph)
            } }.toString())

            val messages = AgentBundle.install(targets, command)
            assertFalse(targets.codexConfig.readText().contains("reaktor-graph"))
            assertEquals(setOf("pencil", "reaktor"), Json.parseToJsonElement(targets.claudeProjectConfig.readText()).jsonObject.getValue("mcpServers").jsonObject.keys)
            assertEquals("edited", Json.parseToJsonElement(targets.antigravityConfig.readText()).jsonObject.getValue("mcpServers").jsonObject
                .getValue("reaktor-graph").jsonObject.getValue("command").jsonPrimitive.content, "an entry its owner changed is not ours to remove")
            assertTrue(messages.any { it.startsWith("antigravity/reaktor-graph: modified") }, messages.toString())
            assertFalse(Json.parseToJsonElement(targets.manifest.readText()).jsonObject.getValue("entries").jsonObject.keys.any { it.endsWith("reaktor-graph") })
        } finally { home.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun codexShellsInTheProjectReachTheOwnerOverLoopbackAndUninstallGivesTheFileBack() {
        val home = Files.createTempDirectory("bundle-home").toFile()
        val root = Files.createTempDirectory("bundle-root").toFile()
        try {
            val targets = AgentBundle.targets(root, home)
            val original = "model = \"x\"\n\n[mcp_servers.pencil]\ncommand = \"pencil-mcp\"\n"
            targets.codexConfig.apply { parentFile.mkdirs() }.writeText(original)
            val messages = AgentBundle.install(targets, command)
            val installed = targets.codexConfig.readText()
            assertEquals(listOf("default_permissions = \"reaktor\"", "features.network_proxy = true", "permissions.reaktor.extends = \":workspace\"",
                "permissions.reaktor.network.enabled = true", "permissions.reaktor.network.allow_local_binding = true"),
                installed.substringBefore(original).lines().filter { it.isNotBlank() && !it.startsWith("#") }, "root keys have to come before the first table")
            assertTrue(installed.contains(original))
            assertTrue(messages.any { it.startsWith("codex/reaktor-sandbox: installed") && it.contains("experimental network proxy") }, messages.toString())
            assertTrue(AgentBundle.status(targets).codexSandbox)

            AgentBundle.uninstall(targets)
            assertEquals(original, targets.codexConfig.readText())
            assertFalse(AgentBundle.status(targets).codexSandbox)
        } finally { home.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun aProjectThatAlreadyChoosesItsPermissionsOrFeaturesKeepsThem() {
        listOf("default_permissions = \"mine\"\n\n[permissions.mine]\nextends = \":read-only\"\n", "[features]\nweb_search = true\n").forEach { original ->
            val home = Files.createTempDirectory("bundle-home").toFile()
            val root = Files.createTempDirectory("bundle-root").toFile()
            try {
                val targets = AgentBundle.targets(root, home)
                targets.codexConfig.apply { parentFile.mkdirs() }.writeText(original)
                val messages = AgentBundle.install(targets, command)
                assertTrue(messages.any { it.startsWith("codex/reaktor-sandbox: collision") }, messages.toString())
                assertTrue(targets.codexConfig.readText().startsWith(original), "a second [features] or default_permissions would not parse")
                assertFalse(AgentBundle.status(targets).codexSandbox)
                AgentBundle.uninstall(targets)
                assertEquals(original, targets.codexConfig.readText())
            } finally { home.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
        }
    }

    @Test fun theLauncherPutsTheSameCommandOnThePathAndPassesEveryArgumentThrough() {
        val home = Files.createTempDirectory("bundle-home").toFile()
        val root = Files.createTempDirectory("bundle-root").toFile()
        try {
            val cli = java.io.File(root, "bin/reaktor's cli").apply { parentFile.mkdirs(); writeText("#!/bin/sh\nfor a in \"\$@\"; do echo \"[\$a]\"; done\n"); setExecutable(true) }
            val launch = listOf(cli.path, "workspace", "mcp", "--dir", root.path)
            val targets = AgentBundle.targets(root, home)
            assertEquals(java.io.File(home, ".local/bin/reaktor-agent"), targets.launcher)
            AgentBundle.install(targets, launch)
            assertTrue(AgentBundle.status(targets).launcher)
            val process = ProcessBuilder(targets.launcher.path, "atlas", "query", "{\"from\": {\"name\": \"A b\"}}").redirectErrorStream(true).start()
            assertEquals("[atlas]\n[query]\n[{\"from\": {\"name\": \"A b\"}}]\n", process.inputStream.readAllBytes().decodeToString())
            assertEquals(0, process.waitFor())
            AgentBundle.uninstall(targets)
            assertFalse(targets.launcher.exists())

            AgentBundle.install(targets, launch)
            targets.launcher.writeText("#!/bin/sh\nexec mine \"\$@\"\n")
            assertTrue(AgentBundle.install(targets, launch).any { it.startsWith("path/reaktor-agent: collision") })
            AgentBundle.uninstall(targets)
            assertEquals("#!/bin/sh\nexec mine \"\$@\"\n", targets.launcher.readText(), "a launcher someone changed is theirs")
        } finally { home.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun aFileWithNothingOfOursToChangeKeepsItsFormatting() {
        val home = Files.createTempDirectory("bundle-home").toFile()
        val root = Files.createTempDirectory("bundle-root").toFile()
        try {
            val targets = AgentBundle.targets(root, home)
            val pretty = "{\n  \"mcpServers\": {\n    \"reaktor\": {\n      \"command\": \"operator-owned\"\n    }\n  }\n}\n"
            targets.claudeProjectConfig.writeText(pretty)
            AgentBundle.install(targets, command)
            assertEquals(pretty, targets.claudeProjectConfig.readText(), "a tracked file is not rewritten when its content does not change")
            AgentBundle.uninstall(targets)
            assertEquals(pretty, targets.claudeProjectConfig.readText())
        } finally { home.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun aLongClasspathMovesIntoAnArgumentFileAndAShortCommandIsLeftAsItIs() {
        val directory = Files.createTempDirectory("bundle-launcher").toFile()
        try {
            val classpath = (1..200).joinToString(":") { "/Applications/Reaktor Preview.app/Contents/app/library-$it.jar" }
            val argfile = java.io.File(directory, "launcher.argfile")
            val compact = AgentBundle.compact(listOf("/jdk/bin/java", "-cp", classpath, "dev.Main", "workspace", "mcp", "--dir", "/tmp/ws"), argfile)
            assertEquals(listOf("/jdk/bin/java", "@${argfile.absolutePath}", "workspace", "mcp", "--dir", "/tmp/ws"), compact)
            assertEquals("-cp\n\"$classpath\"\ndev.Main\n", argfile.readText(), "quoted, because an app bundle's path has spaces")
            val short = listOf("reaktor", "workspace", "mcp", "--dir", "/tmp/ws")
            assertEquals(short, AgentBundle.compact(short, argfile))
        } finally { directory.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }
}
