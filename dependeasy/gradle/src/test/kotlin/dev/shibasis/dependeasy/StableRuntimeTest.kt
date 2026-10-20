package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class StableRuntimeTest {
    @TempDir lateinit var directory: File

    private fun runner(fail: Boolean = false) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
        .withArguments("run", "-PfixtureFail=$fail", "--configuration-cache", "--stacktrace")

    @Test fun `runtime releases leases on successful and failed launches with configuration cache`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"runtimeFixture\"")
        directory.resolve("src/main/kotlin").mkdirs()
        directory.resolve("src/main/kotlin/Entry.kt").writeText("""
            package dev.shibasis.reaktor.core.build
            @Target(AnnotationTarget.FUNCTION)
            @Retention(AnnotationRetention.BINARY)
            annotation class Entry
        """.trimIndent())
        directory.resolve("src/main/kotlin/Fixture.kt").writeText("""
            import dev.shibasis.reaktor.core.build.Entry
            @Entry fun main(arguments: Array<String>) {
                println("fixture launched")
                if (arguments.single().toBoolean()) kotlin.system.exitProcess(7)
            }
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.components.desktopApp
            import dev.shibasis.dependeasy.desktop.RuntimeLeaseService
            import dev.shibasis.dependeasy.model.*
            plugins { id("dev.shibasis.dependeasy.desktop-app") }
            dependencies { implementation(compose.runtime) }
            desktopApp(BuildOption.string("fixture.name", "Fixture"), setOf(Installer.Dmg))
            afterEvaluate {
                tasks.named<JavaExec>("run") { args(providers.gradleProperty("fixtureFail").get()) }
                gradle.sharedServices.registrations.forEach { registration ->
                    val parameters = registration.parameters
                    if (parameters is RuntimeLeaseService.Parameters) parameters.directory.set(layout.buildDirectory.dir("runtime"))
                }
            }
        """.trimIndent())
        val leases = directory.resolve("build/runtime/launches")
        assertContains(runner().build().output, "fixture launched")
        assertTrue(leases.listFiles().orEmpty().isEmpty())
        assertContains(runner().build().output, "Reusing configuration cache")
        assertContains(runner(true).buildAndFail().output, "non-zero exit value 7")
        assertTrue(leases.listFiles().orEmpty().isEmpty())
    }
}
