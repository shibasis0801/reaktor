package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.web.PnpmInstallation
import dev.shibasis.dependeasy.files.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class PnpmInstallationTest {
    @TempDir lateinit var directory: File

    @Test fun `only existing incomplete registry entries require repair`() {
        val manifest = directory.resolve("package.json").apply {
            writeText("""{"dependencies":{"registry":"1.0.0","local":"link:../source","absent":"1.0.0"}}""")
        }
        directory.resolve("node_modules/local").mkdirs()
        assertFalse(PnpmInstallation.needsRepair(setOf(manifest)))
        val registry = directory.resolve("node_modules/registry").apply { mkdirs() }
        assertTrue(PnpmInstallation.needsRepair(setOf(manifest)))
        registry.resolve("package.json").writeText("""{"name":"registry","version":"1.0.0"}""")
        assertFalse(PnpmInstallation.needsRepair(setOf(manifest)))
    }

    @Test fun `dangling registry links require repair without following local package links`() {
        val manifest = directory.resolve("package.json").apply {
            writeText("""{"devDependencies":{"registry":"1.0.0"},"optionalDependencies":{"local":"workspace:*"}}""")
        }
        val modules = directory.resolve("node_modules").apply { mkdirs() }
        try {
            Files.createSymbolicLink(modules.resolve("local").toPath(), directory.resolve("missing-local").toPath())
            assertFalse(PnpmInstallation.needsRepair(setOf(manifest)))
            Files.createSymbolicLink(modules.resolve("registry").toPath(), directory.resolve("missing-store").toPath())
            assertTrue(PnpmInstallation.needsRepair(setOf(manifest)))
        } finally { modules.deleteTreeSafely(within = directory) }
    }
}
