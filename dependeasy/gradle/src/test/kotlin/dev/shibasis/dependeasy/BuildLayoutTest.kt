package dev.shibasis.dependeasy

import kotlinx.serialization.json.*
import dev.shibasis.dependeasy.model.*
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
            .withArguments("exportBuildModel", "--configuration-cache", "--stacktrace").build()
        run()
        assertTrue(directory.resolve(BuildModelReader.RelativePath).isFile)
        val report = Json.decodeFromString<RemoteHostsContract>(directory.resolve("build/dependeasy/remote/hosts.json").readText())
        assertEquals(2, report.schema)
        assertEquals(mapOf("product" to ".", "framework" to "framework", "Other" to "other"),
            report.builds)
        val expected = mapOf(":produceSite" to "web", ":emitPackage" to "web")
        assertEquals(expected, report.taskFamilies.filterKeys { it in expected })
        val model = BuildModelReader.read(directory)
        assertEquals(dev.shibasis.dependeasy.model.NodeKind.Verification, model.nodes.single { it.id.name == "validateContract" }.kind)
        model.nodes.filter { it.artifactFamily != null }.forEach { node ->
            assertEquals(node.artifactFamily, report.taskFamilies[node.id.project.trimEnd(':') + ":" + node.id.name])
        }
        assertContains(run().output, "Reusing configuration cache")
    }
}
