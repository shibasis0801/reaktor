package dev.shibasis.dependeasy

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AppleConfigurationTest {
    @TempDir lateinit var directory: File

    @Test fun `Apple declarations configure before the Kotlin hierarchy creates shared source sets`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"appleFixture\"")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.darwin.darwin
            plugins {
                id("dev.shibasis.dependeasy.pipeline")
                id("org.jetbrains.kotlin.multiplatform")
            }
            kotlin { jvm(); darwin {} }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("help", "--configuration-cache", "--stacktrace").build()
        assertEquals(TaskOutcome.SUCCESS, run().task(":help")?.outcome)
        val repeated = run()
        assertContains(repeated.output, "Reusing configuration cache")
        assertNull(repeated.task(":pnpmInstall"))
        assertNull(repeated.task(":compileKotlinIosArm64"))
    }
}
