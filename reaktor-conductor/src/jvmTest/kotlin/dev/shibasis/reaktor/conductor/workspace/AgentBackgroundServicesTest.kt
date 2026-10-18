package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class AgentBackgroundServicesTest {
    private val temporary = File(System.getProperty("java.io.tmpdir"))

    @Test fun refusesFoldersThatAreNoWorkspaceAndFindsTheEnclosingRepository() {
        val home = Files.createTempDirectory("services-home").toFile()
        try {
            val repository = File(home, "dev/bestbuds").apply { File(this, ".git").mkdirs() }
            val module = File(repository, "modules/engine").apply { mkdirs(); File(this, "settings.gradle.kts").writeText("") }
            listOf(File("/"), home, temporary).forEach { folder ->
                val refusal = assertNotNull(AgentWorkspaceRoots.refusal(folder, home), "${folder.path} is refused")
                assertTrue("--dir" in refusal, refusal)
            }
            assertNull(AgentWorkspaceRoots.refusal(repository, home))
            assertEquals(repository.canonicalFile, AgentWorkspaceRoots.enclosing(module, home), "the repository wins over a build nested in it")
            assertEquals(module.canonicalFile, AgentWorkspaceRoots.enclosing(module, File(home, "dev/bestbuds/modules")), "a build is found when no repository is in reach")
            assertNull(AgentWorkspaceRoots.enclosing(File(home, "dev").apply { mkdirs() }, home), "nothing between the folder and the home folder marks a workspace")
            assertTrue(AgentWorkspaceRoots.temporary(repository))
            assertFalse(AgentWorkspaceRoots.temporary(File(System.getProperty("user.dir"))))
        } finally {
            home.deleteTreeSafely(within = temporary)
        }
    }

    @Test fun listsEveryOwnerAndPrunesOnlyTheOnesThatNeedIt() {
        val home = Files.createTempDirectory("services-home").toFile()
        try {
            val agents = File(home, "Library/LaunchAgents").apply { mkdirs() }
            val live = File(System.getProperty("user.dir")).canonicalPath
            fun seed(name: String, root: String, vararg arguments: String): File {
                val directory = File(home, ".reaktor/agents/$name").apply { mkdirs() }
                val content = AgentBackgroundService.configuration("${AgentBackgroundServices.Prefix}$name", listOf("/usr/bin/java", "-cp", "lib/*") + arguments, File(root), directory.toPath())
                File(directory, "launch-agent.plist").writeText(content)
                return File(agents, "${AgentBackgroundServices.Prefix}$name.plist").apply { writeText(content) }
            }
            val owner = arrayOf("ai.bestbuds.reaktor.kernel.KernelAgentServiceKt")
            val healthy = seed("live", live, *owner)
            seed("gone", File(home, "gone").path, *owner)
            seed("conductor", live, "dev.shibasis.reaktor.conductor.cli.ConductorCliKt", "workspace", "serve", "--dir", live)
            seed("root", "/", *owner)
            val unrelated = File(agents, "com.example.other.plist").apply { writeText("<plist/>") }
            val loaded = setOf("${AgentBackgroundServices.Prefix}live", "${AgentBackgroundServices.Prefix}root")

            val listed = AgentBackgroundServices.list(home, loaded).associateBy { it.label.removePrefix(AgentBackgroundServices.Prefix) }
            assertEquals(setOf("live", "gone", "conductor", "root"), listed.keys)
            assertEquals(BackgroundKind.ConductorService, listed.getValue("conductor").kind)
            assertEquals(BackgroundKind.Owner, listed.getValue("gone").kind)
            assertEquals(live, listed.getValue("live").folder)
            assertTrue(listed.getValue("root").loaded && !listed.getValue("gone").loaded)
            assertEquals(mapOf("live" to null, "gone" to BackgroundProblem.Missing, "conductor" to BackgroundProblem.Conductor, "root" to BackgroundProblem.Unsafe),
                listed.mapValues { it.value.problem(home) })
            val flagged = AgentBackgroundServices.flagged(listed.values.toList(), home)
            assertEquals("1 is for a folder that no longer exists, 1 is an old conductor service and 1 serves /, the home folder or a temporary folder.",
                AgentBackgroundServices.describe(flagged))

            val listing = mutableListOf<String>()
            AgentBackgroundServices.report(prune = false, out = { listing += it }, home = home, loaded = loaded, bootout = { fail("listing stops nothing") })
            assertEquals(3, listing.count { "  remove: it " in it }, listing.joinToString("\n"))
            assertTrue(listing.any { it.startsWith("4 background owners are installed for this user, 2 of them loaded.") }, listing.joinToString("\n"))
            assertEquals(5, agents.listFiles().orEmpty().size, "listing removes nothing")

            val stopped = mutableListOf<String>()
            val pruning = mutableListOf<String>()
            AgentBackgroundServices.report(prune = true, out = { pruning += it }, home = home, loaded = loaded, bootout = { stopped += it; null })
            assertEquals(listOf("${AgentBackgroundServices.Prefix}root"), stopped, "only a loaded owner that needs removing is stopped")
            assertEquals(setOf(healthy, unrelated), agents.listFiles().orEmpty().toSet())
            assertTrue(File(home, ".reaktor/agents/live/launch-agent.plist").isFile)
            listOf("gone", "conductor", "root").forEach { assertFalse(File(home, ".reaktor/agents/$it/launch-agent.plist").exists(), "$it's own copy goes too") }
            assertEquals("Removed 3. 1 owner remains.", pruning.last())
        } finally {
            home.deleteTreeSafely(within = temporary)
        }
    }

    @Test fun launchdKeepsAnOwnerAliveOnlyWhileItsFolderExists() {
        val content = AgentBackgroundService.configuration("label", listOf("/usr/bin/java"), File("/Users/someone/dev/bestbuds"), File("/Users/someone/.reaktor/agents/x").toPath())
        assertTrue("<key>KeepAlive</key><dict><key>PathState</key><dict><key>/Users/someone/dev/bestbuds</key><true/></dict></dict>" in content, content)
    }
}
