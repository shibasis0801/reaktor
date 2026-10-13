package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains

class KotlinProcessorWiringTest {
    @TempDir lateinit var directory: File

    @Test fun `metadata processor is planned without reading half registered task properties`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"processorFixture\"")
        directory.resolve("processor.jar").outputStream().use { ZipOutputStream(it).close() }
        directory.resolve("build.gradle.kts").writeText("""
            plugins {
                id("dev.shibasis.dependeasy.pipeline")
                kotlin("multiplatform")
            }
            repositories { mavenCentral() }
            kotlin { jvm(); js { nodejs() } }
            dependeasy.kotlinProcessor(files("processor.jar"))
        """.trimIndent())
        directory.resolve("src/commonMain/kotlin/Value.kt").apply {
            parentFile.mkdirs(); writeText("val value = 1")
        }
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("compileKotlinJvm", "compileKotlinJs", "--dry-run", "--configuration-cache", "--stacktrace").build()
        val first = run()
        assertContains(first.output, ":kspCommonMainKotlinMetadata SKIPPED")
        assertContains(first.output, ":compileKotlinJvm SKIPPED")
        assertContains(first.output, ":compileKotlinJs SKIPPED")
        assertContains(run().output, "Reusing configuration cache")
    }
}
