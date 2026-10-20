package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains

class KotlinProcessorWiringTest {
    @TempDir lateinit var directory: File

    @Test fun `metadata processor is planned without reading half registered task properties`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"processorFixture\"; include(\"processor\")")
        directory.resolve("processor/build.gradle.kts").apply {
            parentFile.mkdirs(); writeText("plugins { `java-library` }")
        }
        directory.resolve("gradle.properties").writeText("android.newDsl=false\nandroid.builtInKotlin=false\n")
        directory.resolve("build.gradle.kts").writeText("""
            import dev.shibasis.dependeasy.components.library
            import dev.shibasis.dependeasy.handles.Reaktor
            import dev.shibasis.dependeasy.model.BuildHandle
            import dev.shibasis.dependeasy.model.ModuleHandle
            import dev.shibasis.dependeasy.model.Targets
            plugins { id("dev.shibasis.dependeasy.library") }
            repositories { mavenCentral() }
            library(namespace = "fixture.processor", targets = Targets.Jvm + Targets.Web) {
                dependencies {
                    processors(ModuleHandle(BuildHandle("processorFixture", "fixture"), "processor"), options = mapOf(
                        Reaktor.CompilerOptions.FlexCoderRegistrarPackage to "fixture.generated",
                        Reaktor.CompilerOptions.FlexCoderRegistrarObject to "FixtureCoders",
                    ))
                }
            }
            check(extensions.getByType<com.google.devtools.ksp.gradle.KspExtension>().arguments == mapOf(
                "reaktor.flexcoder.registrar.package" to "fixture.generated",
                "reaktor.flexcoder.registrar.object" to "FixtureCoders",
            ))
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
        kotlin.test.assertTrue(first.output.indexOf(":kspCommonMainKotlinMetadata SKIPPED") < first.output.indexOf(":compileKotlinJvm SKIPPED"))
        kotlin.test.assertTrue(first.output.indexOf(":kspCommonMainKotlinMetadata SKIPPED") < first.output.indexOf(":compileKotlinJs SKIPPED"))
        assertContains(run().output, "Reusing configuration cache")
    }
}
