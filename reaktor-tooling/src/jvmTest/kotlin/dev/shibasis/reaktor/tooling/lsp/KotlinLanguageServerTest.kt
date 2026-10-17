package dev.shibasis.reaktor.tooling.lsp

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinLanguageServerTest {
    private fun launcher(directory: File, name: String = "kotlin-lsp.sh"): File {
        directory.mkdirs()
        val file = File(directory, name)
        file.writeText("#!/bin/sh\nexit 0\n")
        file.setExecutable(true)
        return file
    }

    @Test fun reportsNothingWhenNoServerIsInstalled() {
        val home = Files.createTempDirectory("reaktor-lsp-home").toFile()
        assertNull(KotlinLanguageServer.locate(environment = emptyMap(), home = home, path = ""))
    }

    @Test fun findsTheLauncherNamedByTheEnvironment() {
        val root = Files.createTempDirectory("reaktor-lsp-env").toFile()
        val script = launcher(root)
        val found = KotlinLanguageServer.locate(
            environment = mapOf("REAKTOR_KOTLIN_LSP" to script.absolutePath),
            home = File("/nonexistent"),
            path = "",
        )
        assertNotNull(found)
        assertEquals(listOf(script.absolutePath, "--stdio"), found.command)
        assertEquals("REAKTOR_KOTLIN_LSP", found.origin)
    }

    @Test fun acceptsADirectoryForTheEnvironmentOverride() {
        val root = Files.createTempDirectory("reaktor-lsp-dir").toFile()
        val script = launcher(root)
        val found = KotlinLanguageServer.locate(
            environment = mapOf("REAKTOR_KOTLIN_LSP" to root.absolutePath),
            home = File("/nonexistent"),
            path = "",
        )
        assertEquals(script.absolutePath, found?.command?.first())
    }

    @Test fun findsTheWellKnownReaktorLocation() {
        val home = Files.createTempDirectory("reaktor-lsp-wellknown").toFile()
        val script = launcher(File(home, ".reaktor/kotlin-lsp"))
        val found = KotlinLanguageServer.locate(environment = emptyMap(), home = home, path = "")
        assertEquals(script.absolutePath, found?.command?.first())
    }

    @Test fun findsALauncherOnThePath() {
        val binary = Files.createTempDirectory("reaktor-lsp-path").toFile()
        val script = launcher(binary, name = "kotlin-lsp")
        val found = KotlinLanguageServer.locate(
            environment = emptyMap(),
            home = File("/nonexistent"),
            path = "/definitely/missing${File.pathSeparatorChar}${binary.absolutePath}",
        )
        assertEquals(script.absolutePath, found?.command?.first())
        assertEquals("PATH", found?.origin)
    }

    @Test fun ignoresALauncherThatIsNotExecutable() {
        val home = Files.createTempDirectory("reaktor-lsp-noexec").toFile()
        val directory = File(home, ".reaktor/kotlin-lsp").apply { mkdirs() }
        File(directory, "kotlin-lsp.sh").writeText("#!/bin/sh\n")
        File(directory, "kotlin-lsp.sh").setExecutable(false)
        assertNull(KotlinLanguageServer.locate(environment = emptyMap(), home = home, path = ""))
    }

    @Test fun missingServerDegradesToAnUnavailableIntelligenceWithARemedy() = runBlocking {
        val root = Files.createTempDirectory("reaktor-lsp-root").toFile()
        val intelligence = KotlinLanguageServer.start(
            root = root,
            environment = emptyMap(),
            home = File("/nonexistent"),
        )
        assertFalse(intelligence.status.available)
        assertEquals(KotlinLanguageServer.Name, intelligence.status.name)
        assertTrue(intelligence.status.remedy!!.contains("kotlin-lsp"))
        assertTrue(intelligence.completions("reaktor://x", dev.shibasis.reaktor.code.CodePosition.Start).isEmpty())
    }
}
