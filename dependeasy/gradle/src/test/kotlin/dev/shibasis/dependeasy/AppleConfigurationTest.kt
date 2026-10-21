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
            import dev.shibasis.dependeasy.tasks.GenerateNativeDefTask
            plugins {
                id("dev.shibasis.dependeasy.pipeline")
                id("org.jetbrains.kotlin.multiplatform")
            }
            kotlin { jvm(); darwin {} }
            tasks.register<GenerateNativeDefTask>("nativeDef") {
                baseDefFile.set(layout.projectDirectory.file("optional.def"))
                headerFiles.from(layout.projectDirectory.file("Runtime.h"))
                outputFile.set(layout.buildDirectory.file("native.def"))
            }
        """.trimIndent())
        directory.resolve("Runtime.h").writeText("void fixture();\n")
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("help", "nativeDef", "--configuration-cache", "--stacktrace").build()
        val first = run()
        assertEquals(TaskOutcome.SUCCESS, first.task(":help")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":nativeDef")?.outcome)
        val generated = directory.resolve("build/native.def")
        val headersOnly = generated.readText()
        assertContains(headersOnly, "Runtime.h")
        val repeated = run()
        assertContains(repeated.output, "Reusing configuration cache")
        assertNull(repeated.task(":pnpmInstall"))
        assertNull(repeated.task(":compileKotlinIosArm64"))
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":nativeDef")?.outcome)
        directory.resolve("optional.def").writeText("language = Objective-C\n")
        val withBase = run()
        assertContains(withBase.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.SUCCESS, withBase.task(":nativeDef")?.outcome)
        assertContains(generated.readText(), "language = Objective-C")
        assertTrue(directory.resolve("optional.def").delete())
        val withoutBase = run()
        assertContains(withoutBase.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.SUCCESS, withoutBase.task(":nativeDef")?.outcome)
        assertEquals(headersOnly, generated.readText())
    }
}
