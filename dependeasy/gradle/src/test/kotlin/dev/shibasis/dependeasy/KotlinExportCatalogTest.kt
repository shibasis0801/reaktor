package dev.shibasis.dependeasy

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class KotlinExportCatalogTest {
    @TempDir lateinit var directory: File

    @Test fun `custom exports are discoverable without compiling or installing backend tools`() {
        directory.resolve("settings.gradle.kts").writeText("rootProject.name = \"catalogFixture\"\ninclude(\"library\")")
        directory.resolve("build.gradle.kts").writeText("plugins { id(\"dev.shibasis.dependeasy.pipeline\") }")
        directory.resolve("library/build.gradle.kts").apply {
            parentFile.mkdirs()
            writeText("""
                import dev.shibasis.dependeasy.web.web
                plugins { id("org.jetbrains.kotlin.multiplatform") }
                kotlin { web { exportDirectory = "custom/generated" } }
            """.trimIndent())
        }
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("reportKotlinExports", "--configuration-cache", "--stacktrace").build()
        val first = run()
        assertEquals(TaskOutcome.SUCCESS, first.task(":reportKotlinExports")?.outcome)
        assertNull(first.task(":pnpmInstall"))
        assertNull(first.task(":library:exportKotlinLibrary"))
        val metadata = Json.parseToJsonElement(directory.resolve("build/dependeasy/kotlin-exports.json").readText()).jsonObject
        assertEquals(listOf("library/custom/generated"), metadata.getValue("directories").jsonArray.map { it.jsonPrimitive.content })
        val repeated = run()
        assertContains(repeated.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":reportKotlinExports")?.outcome)
    }
}
