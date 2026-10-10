package dev.shibasis.dependeasy

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
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy {
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
            .withArguments("publishToGithubPackages", "-PpublicationFixtureVersion=7.2", "--configuration-cache").build()
        val first = run()
        assertEquals(TaskOutcome.SUCCESS, first.task(":library:publishJavaPublicationToLocalFixtureRepository")?.outcome)
        assertTrue(directory.resolve("build/repository/fixture/library/7.2/library-7.2.jar").isFile)
        assertContains(run().output, "Reusing configuration cache")
    }
}
