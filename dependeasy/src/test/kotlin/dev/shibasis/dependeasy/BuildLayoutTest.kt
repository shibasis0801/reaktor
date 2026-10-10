package dev.shibasis.dependeasy

import kotlinx.serialization.json.*
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class BuildLayoutTest {
    @TempDir lateinit var directory: File

    @Test fun `a diamond composite reports the shared framework once and reuses configuration cache`() {
        directory.resolve("settings.gradle.kts").writeText("""
            rootProject.name = "product"
            includeBuild("framework")
            includeBuild("other") { name = "Other" }
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy {
                javascript("website").build("produceSite")
                javascript("api").verify("validateContract")
                javascript("bindings").library("emitPackage", "tsconfig.json", "dist")
            }
        """.trimIndent())
        for (name in listOf("framework", "other")) {
            val build = directory.resolve(name).apply { mkdir() }
            val identity = if (name == "other") "Other" else name
            build.resolve("settings.gradle.kts").writeText("rootProject.name = \"$identity\"" +
                if (name == "other") "\nincludeBuild(\"../framework\")" else "")
            build.resolve("build.gradle.kts").writeText("")
        }
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("reportBuildLayout", "--configuration-cache", "--stacktrace").build()
        run()
        val report = Json.parseToJsonElement(directory.resolve("build/dependeasy/build-layout.json").readText()).jsonObject
        assertEquals(1, report.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(mapOf("product" to ".", "framework" to "framework", "Other" to "other"),
            report.getValue("builds").jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertEquals(mapOf(":produceSite" to "web", ":validateContract" to "web", ":emitPackage" to "web"),
            report.getValue("taskFamilies").jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertContains(run().output, "Reusing configuration cache")
    }
}
