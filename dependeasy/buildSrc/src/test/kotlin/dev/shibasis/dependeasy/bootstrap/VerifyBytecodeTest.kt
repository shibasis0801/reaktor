package dev.shibasis.dependeasy.bootstrap

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerifyBytecodeTest {
    @TempDir lateinit var directory: Path

    @Test fun acceptsJava21() {
        fixture(21)
        val result = runner().build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyBytecode")?.outcome)
        assertTrue(result.output.contains("Verified 1 classes with Java 21 bytecode"))
    }

    @Test fun rejectsJava25() {
        fixture(25)
        val result = runner().buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":verifyBytecode")?.outcome)
        assertTrue(result.output.contains("bytecode major 69; expected 65 (Java 21)"))
    }

    private fun fixture(release: Int) {
        directory.resolve("settings.gradle.kts").toFile().writeText("rootProject.name = \"bytecode-fixture\"")
        directory.resolve("build.gradle.kts").toFile().writeText("""
            plugins { id("dev.shibasis.dependeasy.bytecode") }
            tasks.withType<JavaCompile>().configureEach { options.release.set($release) }
        """.trimIndent())
        directory.resolve("src/main/java/Fixture.java").toFile().apply {
            parentFile.mkdirs()
            writeText("public final class Fixture {}")
        }
    }

    private fun runner() = GradleRunner.create().withProjectDir(directory.toFile())
        .withPluginClasspath().withArguments("verifyBytecode", "--configuration-cache", "--console=plain")
}
