package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class DependencyRepositoriesTest {
    @TempDir lateinit var directory: File

    @Test fun `ordinary builds exclude machine local dependencies and retain repository escape hatch`() {
        directory.resolve("settings.gradle.kts").writeText("""
            rootProject.name = "repositoryFixture"
            include(":child")
        """.trimIndent())
        directory.resolve("build.gradle.kts").writeText("""
            import org.gradle.api.artifacts.repositories.MavenArtifactRepository
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            dependeasy {
                dependencyRepositories { maven { name = "Fixture"; url = uri("repository") } }
            }
            tasks.register("reportRepositories") {
                inputs.property("repositories", project(":child").repositories
                    .filterIsInstance<MavenArtifactRepository>().map { it.name })
                doLast { logger.lifecycle("repositories=" + inputs.properties["repositories"]) }
            }
        """.trimIndent())
        directory.resolve("child").mkdirs()
        fun run(vararg arguments: String) = GradleRunner.create().withProjectDir(directory)
            .withPluginClasspath().withArguments("reportRepositories", "--configuration-cache", *arguments).build()
        val ordinary = run()
        assertContains(ordinary.output, "MavenRepo")
        assertContains(ordinary.output, "Fixture")
        assertFalse(ordinary.output.contains("MavenLocal"))
        assertContains(run().output, "Reusing configuration cache")
        assertContains(run("-Pdependeasy.useMavenLocal=true").output, "MavenLocal")
    }
}
