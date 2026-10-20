package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.BuildModelReader
import dev.shibasis.dependeasy.model.SourceSite
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublishingTest {
    @TempDir lateinit var directory: File

    @Test fun `workspace publications support later module plugins and version overrides`() {
        directory.resolve("settings.gradle.kts").writeText("""
            rootProject.name = "publicationFixture"
            include("library")
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            plugins { id("dev.shibasis.dependeasy.workspace") }
            workspace {
                publishing {
                    group = "fixture"
                    maven("localFixture", layout.buildDirectory.dir("repository").get().asFile.toURI().toString())
                }
            }
        """.trimIndent())
        directory.resolve("library").mkdirs()
        directory.resolve("library/build.gradle.kts").writeText("""
            plugins { java }
            publishing {
                publications.create<MavenPublication>("java") { from(components["java"]) }
            }
        """.trimIndent())
        fun run() = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withArguments("publishToGithubPackages", "exportBuildModel", "-PpublicationFixtureVersion=7.2", "--configuration-cache").build()
        val first = run()
        assertEquals(TaskOutcome.SUCCESS, first.task(":library:publishJavaPublicationToLocalFixtureRepository")?.outcome)
        assertTrue(directory.resolve("build/repository/fixture/library/7.2/library-7.2.jar").isFile)
        val model = BuildModelReader.read(directory)
        assertTrue(model.nodes.none { it.id.project == ":" && it.id.name == "outputModuleBuild" })
        assertEquals(setOf("outputPublishedModulesLocal", "outputPublishedModulesRemote"),
            model.nodes.filter { it.id.project == ":library" && it.id.name.startsWith("outputPublishedModules") }.map { it.id.name }.toSet())
        val publicationSite = SourceSite("publicationFixture", "build.gradle.kts",
            directory.resolve("build.gradle.kts").readLines().indexOfFirst { it.trim() == "publishing {" } + 1)
        assertTrue(model.nodes.filter { it.id.name.startsWith("outputPublishedModules") }.all { it.declaredAt?.copy(symbol = null, callPath = emptyList()) == publicationSite })
        assertEquals(null, first.task(":completeModuleBuild"))
        assertContains(run().output, "Reusing configuration cache")
    }
}
