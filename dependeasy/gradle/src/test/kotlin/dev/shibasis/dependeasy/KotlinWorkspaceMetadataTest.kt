package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.files.deleteTreeSafely
import dev.shibasis.dependeasy.model.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class KotlinWorkspaceMetadataTest {
    @TempDir lateinit var directory: File

    private fun synchronize(root: File, fleet: File? = null) {
        WebContractKernel.write(KernelSpec.WebContract("workspace", directory.absolutePath,
            FileSet(emptyList()), FileSet(emptyList()), FileSet(emptyList()), emptyMap(), emptyMap(),
            FileSet(emptyList()), null, null, null,
            FileSet(listOf(directory.resolve("contract.json").absolutePath)), FileSet(emptyList()),
            workspaceMetadata = FileSet(listOf(root.resolve("package.json").absolutePath)),
            workspaceDefinition = FileSet(listOf(directory.resolve("pnpm-workspace.yaml").absolutePath)),
            workerFleet = FileSet(listOfNotNull(fleet?.absolutePath))))
    }

    @Test fun `synchronization removes obsolete packages and preserves active outputs and linked sources`() {
        directory.resolve("pnpm-workspace.yaml").writeText("packages:\n  - source\n  # Kotlin-generated workspace packages start\n  # Kotlin-generated workspace packages end\nsaveExact: true\n")
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
        Files.createSymbolicLink(old.resolve("source-link").toPath(), source.toPath())
        Files.createSymbolicLink(root.resolve("packages/linked").toPath(), source.toPath())
        Files.createSymbolicLink(root.resolve("packages_imported/linked").toPath(), source.parentFile.toPath())

        val worker = directory.resolve("targets/worker").apply { mkdirs(); resolve("package.json").writeText("{}") }
        val fleet = directory.resolve("fleet.json")
        fleet.writeText("""{"schema":1,"workers":[{"name":"worker","node":{"build":"fixture","project":":workers","name":"worker"},"directory":"targets/worker","configurations":{},"services":{}},{"name":"unpackaged","node":{"build":"fixture","project":":workers","name":"unpackaged"},"directory":"targets/unpackaged","configurations":{},"services":{}}]}""")
        try {
            synchronize(root, fleet)
            val first = directory.resolve("pnpm-workspace.yaml").readText()
            synchronize(root, fleet)
            assertEquals(first, directory.resolve("pnpm-workspace.yaml").readText())
            assertTrue(first.contains("build/js/packages/active"))
            assertTrue(first.contains("build/js/packages_imported/library/2.0"))
            assertFalse(first.contains("removed"))
            assertTrue(first.endsWith("saveExact: true\n"))
            assertContains(first, "targets/worker")
            assertFalse(first.contains("targets/unpackaged"))
            fleet.writeText(fleet.readText().replace("targets/worker", "../escape"))
            assertFailsWith<IllegalArgumentException> { synchronize(root, fleet) }
            assertEquals(first, directory.resolve("pnpm-workspace.yaml").readText())
            fleet.writeText("""{"schema":1,"workers":[]}""")
            synchronize(root, fleet)
            assertFalse(directory.resolve("pnpm-workspace.yaml").readText().contains("targets/worker"))
            assertTrue(worker.resolve("package.json").isFile)

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
        assertFailsWith<IllegalArgumentException> { synchronize(directory) }
        assertTrue(output.isDirectory)
    }
}
