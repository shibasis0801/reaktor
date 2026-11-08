package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.files.deleteTreeSafely
import dev.shibasis.dependeasy.model.IdentitySource
import dev.shibasis.dependeasy.model.KernelSpec
import dev.shibasis.dependeasy.model.SourceIdentityKernel
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Path
import java.util.Properties
import kotlin.test.*

class SourceIdentityTest {
    @TempDir lateinit var directory: File
    @TempDir lateinit var relocationRoot: File

    private fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
        .withArguments("identity", "--configuration-cache", "--stacktrace").build()

    @Test fun `identity ignores missing sources consistently without hiding read failures`() {
        fun digest(files: Set<File>): String {
            val spec = KernelSpec.SourceIdentity("identity.properties",
                listOf(IdentitySource("workspace", directory.path, listOf("."))), emptyList(), false)
            val output = directory.resolve("identity")
            SourceIdentityKernel.generate(spec, mapOf("workspace" to files), emptyMap(), output)
            return Properties().apply { output.resolve(spec.fileName).inputStream().use(::load) }.getProperty("sourceDigest")
        }
        val source = directory.resolve("source.kt").apply { writeText("fun value() = 1\n") }
        val absent = digest(emptySet())
        val present = digest(setOf(source))
        val disappearing = object : File(source.path) {
            override fun isFile(): Boolean = super.isFile().also { if (it) assertTrue(delete()) }
        }
        assertEquals(absent, digest(setOf(disappearing)))
        assertFalse(source.exists())
        assertEquals(absent, digest(setOf(source)))
        assertEquals(absent, digest(emptySet()))
        source.writeText("")
        assertNotEquals(absent, digest(setOf(source)))
        source.writeText("fun value() = 1\n")
        assertEquals(present, digest(setOf(source)))
        val inaccessible = object : File(source.path) {
            override fun toPath(): Path = throw AccessDeniedException(path)
        }
        assertFailsWith<AccessDeniedException> { digest(setOf(inaccessible)) }
    }

    @Test fun `identity is incremental and deterministic across checkout locations`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"identityFixture\"")
        directory.resolve("source").mkdir()
        val source = directory.resolve("source/main.kt").apply { writeText("fun value() = 1\n") }
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.provenance.SourceIdentityTask
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            tasks.register<SourceIdentityTask>("identity") {
                fileName.set("identity.properties")
                sources("workspace", file("source"), fileTree("source"))
                revisions.put("revision", "fixture:revision=1")
            }
        """.trimIndent())
        val output = directory.resolve("build/generated/identity/identity.properties")
        assertEquals(TaskOutcome.SUCCESS, run().task(":identity")?.outcome)
        val original = output.readText()
        val properties = Properties().apply { output.inputStream().use(::load) }
        assertEquals("fixture:revision=1", properties.getProperty("revision"))
        assertFalse(properties.containsKey("builtAt"))
        val repeated = run()
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":identity")?.outcome)
        output.delete()
        run()
        assertEquals(original, output.readText())
        source.writeText("fun value() = 2\n")
        assertEquals(TaskOutcome.SUCCESS, run().task(":identity")?.outcome)
        assertNotEquals(original, output.readText())
        val relocated = relocationRoot.resolve("checkout")
        try {
            relocated.mkdirs()
            listOf("settings.gradle.kts", "build.gradle.kts", "source").forEach {
                directory.resolve(it).copyRecursively(relocated.resolve(it))
            }
            GradleRunner.create().withProjectDir(relocated).withPluginClasspath().withArguments("identity").build()
            assertEquals(output.readText(), relocated.resolve("build/generated/identity/identity.properties").readText())
        } finally { relocated.deleteTreeSafely(within = relocationRoot) }
    }
}
