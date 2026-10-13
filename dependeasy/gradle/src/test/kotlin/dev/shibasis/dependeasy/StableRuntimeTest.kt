package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class StableRuntimeTest {
    @TempDir lateinit var directory: File

    private fun runner(fail: Boolean = false) = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
        .withArguments("launch", "-PfixtureFail=$fail", "--configuration-cache", "--stacktrace")

    @Test fun `runtime releases leases on successful and failed launches with configuration cache`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"runtimeFixture\"")
        directory.resolve("src/main/java").mkdirs()
        directory.resolve("src/main/java/Fixture.java").writeText("""
            public class Fixture {
                public static void main(String[] arguments) {
                    System.out.println("fixture launched");
                    if (Boolean.parseBoolean(arguments[0])) System.exit(7);
                }
            }
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            plugins { java; id("dev.shibasis.dependeasy.pipeline") }
            tasks.register<JavaExec>("launch") {
                classpath = files(tasks.jar) + configurations.runtimeClasspath.get()
                mainClass.set("Fixture")
                args(providers.gradleProperty("fixtureFail").get())
            }
            dependeasy.stableRuntime("launch", layout.buildDirectory.dir("runtime").get().asFile)
        """.trimIndent())
        val leases = directory.resolve("build/runtime/launches")
        assertContains(runner().build().output, "fixture launched")
        assertTrue(leases.listFiles().orEmpty().isEmpty())
        assertContains(runner().build().output, "Reusing configuration cache")
        assertContains(runner(true).buildAndFail().output, "non-zero exit value 7")
        assertTrue(leases.listFiles().orEmpty().isEmpty())
    }
}
