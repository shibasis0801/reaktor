package dev.shibasis.reaktor.conductor.workspace

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AgentServicePathTest {
    @Test fun finderLaunchesCanDiscoverNvmWithoutSourcingShellProfiles() {
        val home = Files.createTempDirectory("agent-path").toFile()
        try {
            val bin = File(home, ".nvm/versions/node/v22/bin").apply { mkdirs() }
            File(bin, "codex").apply { writeText("fixture"); setExecutable(true) }
            val path = agentServicePath("/custom/bin:/usr/bin", home).split(File.pathSeparator)
            assertEquals("/custom/bin", path.first())
            assertTrue(bin.path in path)
            assertEquals(path.distinct(), path)
        } finally { home.deleteRecursively() }
    }
}
