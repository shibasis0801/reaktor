package dev.shibasis.reaktor.tooling

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

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
        } finally { parent.deleteRecursively() }
    }

    private val wrapperName = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew"
    private val suffixes = setOf(".gradle", ".gradle.kts", ".toml", ".properties")
}
