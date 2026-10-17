package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.files.deleteTreeSafely
import dev.shibasis.dependeasy.web.pruneKotlinWorkspace
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class KotlinWorkspaceMetadataTest {
    @TempDir lateinit var directory: File

    @Test fun `synchronization removes obsolete packages and preserves active outputs and linked sources`() {
        val root = directory.resolve("build/js").apply { mkdirs() }
        root.resolve("package.json").writeText("""{"workspaces":["packages/active","packages_imported/library/2.0"]}""")
        fun packageAt(path: String) = root.resolve(path).apply {
            mkdirs(); resolve("package.json").writeText("{}")
        }
        val active = packageAt("packages/active")
        active.resolve("output.mjs").writeText("compiled output")
        val old = packageAt("packages/removed")
        val imported = packageAt("packages_imported/library/2.0")
        val oldVersion = packageAt("packages_imported/library/1.0")
        val source = directory.resolve("source").apply { mkdir(); resolve("package.json").writeText("{}") }
        Files.createSymbolicLink(root.resolve("packages/linked").toPath(), source.toPath())
        Files.createSymbolicLink(root.resolve("packages_imported/linked").toPath(), source.parentFile.toPath())

        try {
            pruneKotlinWorkspace(root)
            pruneKotlinWorkspace(root)

            assertTrue(active.resolve("output.mjs").isFile)
            assertTrue(imported.isDirectory)
            assertFalse(old.exists())
            assertFalse(oldVersion.exists())
        } finally { root.deleteTreeSafely(within = directory) }
        assertTrue(source.resolve("package.json").isFile)
    }

    @Test fun `missing declarations fail before deleting generated output`() {
        val output = directory.resolve("packages/keep").apply { mkdirs(); resolve("package.json").writeText("{}") }
        directory.resolve("package.json").writeText("{}")
        assertFailsWith<IllegalArgumentException> { pruneKotlinWorkspace(directory) }
        assertTrue(output.isDirectory)
    }
}
