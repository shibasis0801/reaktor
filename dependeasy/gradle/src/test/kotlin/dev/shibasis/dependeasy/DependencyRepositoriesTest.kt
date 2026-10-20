package dev.shibasis.dependeasy

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class DependencyRepositoriesTest {
    @TempDir lateinit var directory: File

    @Test fun `pipeline installs repository policy without consumer declarations`() {
        directory.resolve("settings.gradle.kts").writeText("""
            rootProject.name = "automaticRepositoryFixture"
            include(":child")
        """.trimIndent())
        directory.resolve("child").mkdirs()
        directory.resolve("build.gradle.kts").writeText("""
            import org.gradle.api.artifacts.repositories.MavenArtifactRepository
            plugins { id("dev.shibasis.dependeasy.pipeline") }
            tasks.register("reportRepositories") {
                inputs.property("repositories", allprojects.associate { owner ->
                    owner.path to owner.repositories.filterIsInstance<MavenArtifactRepository>().map { it.name }
                })
                doLast { logger.lifecycle("repositories=" + inputs.properties["repositories"]) }
            }
        """.trimIndent())
        fun run(vararg arguments: String) = GradleRunner.create().withProjectDir(directory)
            .withPluginClasspath().withArguments("reportRepositories", "--configuration-cache", *arguments).build()
        val ordinary = run()
        assertContains(ordinary.output, ":=[MavenRepo, Google, maven, maven2]")
        assertContains(ordinary.output, ":child=[MavenRepo, Google, maven, maven2]")
        assertFalse(ordinary.output.contains("MavenLocal"))
        assertContains(run().output, "Reusing configuration cache")
        assertContains(run("-Pdependeasy.useMavenLocal=true").output, "MavenLocal")
    }

}
