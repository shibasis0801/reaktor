package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmProjectDiscoverySealTest {
    @Test
    fun gradleTasksShareOneCompleteDiscoverySealWhileProvenanceStaysTaskSpecific() = fixture { root, included ->
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        val declared = workspace.prepare(TaskInvocation(TaskId("target/app/build")))
        val module = workspace.prepare(TaskInvocation(TaskId("gradle/module0/build")))
        val first = assertNotNull(declared.request.definitionSeal)
        val second = assertNotNull(module.request.definitionSeal)

        assertSame(first.files, second.files, "One builder must reuse its captured Gradle definition")
        assertSame(first.directories, second.directories)
        assertEquals(declared.request.argv, module.request.argv)
        assertNotEquals(declared.plan.fingerprint, module.plan.fingerprint,
            "Distinct provenance paths must still bind distinct plans")
        assertEquals("package.json#reaktor.targets.app.build",
            workspace.catalog.tasks.first { it.id == declared.plan.taskId }.provenance.path)
        assertEquals("settings.gradle.kts", workspace.catalog.tasks.first { it.id == module.plan.taskId }.provenance.path)

        val expected = ProcessDefinitionSeal.capture(
            files = listOf(File(root, wrapperName), File(root, "settings.gradle.kts"),
                File(root, "build.gradle.kts"), File(root, "gradle.properties")),
            directories = listOf(ProcessDefinitionDirectory(root, suffixes),
                ProcessDefinitionDirectory(File(root, "buildSrc")), ProcessDefinitionDirectory(included, suffixes)),
        )
        assertEquals(expected, first, "Reuse must preserve the exact pre-existing definition closure")
    }

    @Test
    fun laterDiscoveryAndBothExecutionGatesRecaptureChangedDefinitions() = fixture { root, included ->
        val discovery = JvmProjectDiscovery()
        val first = assertNotNull(discovery.discoverWorkspace(root))
        val invocation = TaskInvocation(TaskId("gradle/module0/build"))
        val prepared = first.prepare(invocation)
        val oldSeal = assertNotNull(prepared.request.definitionSeal)
        File(included, "added.gradle.kts").writeText("// New included-build member\n")

        assertFailsWith<IllegalStateException> { first.prepare(invocation) }
        SupervisedProcessExecutor().use { executor ->
            assertFailsWith<IllegalArgumentException> { executor.start(prepared.request) }
        }

        val second = assertNotNull(discovery.discoverWorkspace(root))
        val refreshed = assertNotNull(second.prepare(invocation).request.definitionSeal)
        assertNotSame(oldSeal.files, refreshed.files, "The same discovery object must create a fresh builder per pass")
        assertNotEquals(oldSeal.digest, refreshed.digest)
        File(root, "buildSrc/Rules.kt").appendText("// Changed build logic\n")
        assertFailsWith<IllegalStateException> { second.prepare(invocation) }
    }

    @Test
    fun generatedWorkerSealsOnlyItsManifestClosureAndRejectsChangedImports() = fixture { root, _ ->
        worker(root)
        val bundle = File(root, "modules/app/bestbuds-kt").apply { mkdirs() }.canonicalFile
        repeat(30_000) { File(bundle, "unused-$it.mjs").writeText("export const unused = $it;") }
        val entry = File(bundle, "worker.mjs").apply { writeText("export { value } from './dependency.mjs';") }
        val dependency = File(bundle, "dependency.mjs").apply { writeText("export const value = 1;") }
        bundleManifest(root, listOf(entry, dependency))
        DefinitionDigestCache.invalidate()
        val start = System.nanoTime()
        val workspace = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMillis < 3_000, "30,000-file generated bundle discovery took $elapsedMillis ms")
        val invocation = TaskInvocation(TaskId("target/worker/npm/deploy"))
        assertNull(workspace.catalog.tasks.first { it.id == invocation.taskId }.unavailableReason)
        val prepared = workspace.prepare(invocation)
        val seal = assertNotNull(prepared.request.definitionSeal)
        assertEquals(setOf("worker.mjs", "dependency.mjs", "package.json"), seal.files.filter { it.parentFile == bundle }.map { it.name }.toSet())
        assertTrue(seal.directories.none { it.directory == bundle })
        File(bundle, "unused-1.mjs").writeText("export const unrelated = 10;")
        assertEquals(seal.digest, workspace.prepare(invocation).request.definitionSeal?.digest)
        dependency.writeText("export const value = 2;")
        assertFailsWith<IllegalStateException> { workspace.prepare(invocation) }
        SupervisedProcessExecutor().use { executor -> assertFailsWith<IllegalArgumentException> { executor.start(prepared.request) } }
        val changed = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root))
        assertTrue(changed.catalog.tasks.first { it.id == invocation.taskId }.unavailableReason.orEmpty().contains("manifest is stale"))
        println("worker-manifest discovery-ms=$elapsedMillis files=${seal.files.size}")
    }

    @Test
    fun generatedWorkerMissingIncompleteAndEscapingManifestsFailClosed() = fixture { root, _ ->
        worker(root)
        val entry = File(root, "modules/app/bestbuds-kt/worker.mjs").apply { parentFile.mkdirs(); writeText("export const value = 1;") }
        fun reason(): String = assertNotNull(JvmProjectDiscovery().discoverWorkspace(root)).catalog.tasks
            .first { it.id == TaskId("target/worker/npm/deploy") }.unavailableReason.orEmpty()
        assertTrue(reason().contains("manifest is unavailable"))
        bundleManifest(root, listOf(entry), imported = "bestbuds-kt/other.mjs")
        assertTrue(reason().contains("manifest has no bestbuds-kt/worker.mjs"))
        bundleManifest(root, listOf(entry), path = "../../../package.json")
        assertTrue(reason().contains("path escapes"))
        bundleManifest(root, listOf(entry), path = "missing.mjs")
        assertTrue(reason().contains("member is missing"))
        bundleManifest(root, listOf(entry))
        assertEquals("", reason())
        entry.writeText("export { value } from './unlisted.mjs';")
        File(entry.parentFile, "unlisted.mjs").writeText("export const value = 1;")
        bundleManifest(root, listOf(entry))
        assertTrue(reason().contains("manifest omits a dependency"))
    }

    private fun worker(root: File) {
        File(root, "package.json").writeText("""{"name":"worker-seal","reaktor":{},"workspaces":["targets/worker"]}""")
        File(root, "targets/worker/package.json").apply { parentFile.mkdirs(); writeText("""{"name":"worker","scripts":{"deploy":"wrangler deploy"}}""") }
        File(root, "modules/app/bestbuds-kt/package.json").apply { parentFile.mkdirs(); writeText("""{"name":"bestbuds-kt","main":"worker.mjs"}""") }
        File(root, "targets/worker/src/index.ts").apply { parentFile.mkdirs(); writeText("import { value } from 'bestbuds-kt/worker.mjs';") }
    }

    private fun bundleManifest(root: File, members: List<File>, imported: String = "bestbuds-kt/worker.mjs", path: String? = null) {
        val bundle = File(root, "modules/app/bestbuds-kt").canonicalFile
        val entries = (members + File(bundle, "package.json")).distinct().joinToString(",") { file -> """{"path":"${path ?: file.canonicalFile.relativeTo(bundle).invariantSeparatorsPath}","sha256":"${DefinitionDigestCache.digestOf(file)}"}""" }
        File(root, "modules/app/bestbuds-kt.manifest.json").writeText("""{"format":1,"imports":{"$imported":[$entries]}}""")
    }

    private fun fixture(modules: Int = 2, block: (File, File) -> Unit) {
        val parent = Files.createTempDirectory("reaktor-discovery-seal").toFile()
        try {
            val root = File(parent, "workspace").apply { mkdirs() }
            val included = File(parent, "included").apply { mkdirs() }
            File(root, "package.json").writeText("""{"name":"seal-fixture","reaktor":{"targets":{"app":{"gradle":":module0","build":"build"}}}}""")
            File(root, wrapperName).writeText("fixture wrapper; tests never execute this file\n")
            File(root, "settings.gradle.kts").writeText(
                "includeBuild(\"../included\")\n" + (0 until modules).joinToString("\n") { "include(\":module$it\")" },
            )
            File(root, "build.gradle.kts").writeText("// Fixture build\n")
            File(root, "gradle.properties").writeText("fixture=true\n")
            File(root, "buildSrc/Rules.kt").apply { parentFile.mkdirs(); writeText("// Fixture build logic\n") }
            File(included, "build.gradle.kts").writeText("// Fixture included build\n")
            block(root, included)
        } finally { parent.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }

    private val wrapperName = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew"
    private val suffixes = setOf(".gradle", ".gradle.kts", ".toml", ".properties")
}
