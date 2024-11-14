package dev.shibasis.publisheasy.plugins

import org.gradle.api.*
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.get
import java.io.File
import java.time.LocalDate

fun Project.readVersion(): String {
    val versionFile = File(projectDir, "version")
    val now = LocalDate.now()
    val year = now.year
    val dayOfYear = now.dayOfYear

    if (!versionFile.exists())
        versionFile.writeText("$year.$dayOfYear.0")

    version = versionFile.readText().trim()
    return version.toString()
}


fun Task.updateVersion() {
    group = "reaktor"
    description = "Year.DayOfYear.PublicationThatDay"

    doLast {
        val now = LocalDate.now()
        val currentVersion = project.readVersion()
        val (currentYear, currentDay, currentBuild) = currentVersion.split(".").map { it.toInt() }
        val newBuildNumber = if (currentYear == now.year && currentDay == now.dayOfYear) {
            currentBuild + 1
        } else {
            0
        }

        val newVersion = "${now.year}.${now.dayOfYear}.$newBuildNumber"
        File(project.projectDir, "version").writeText(newVersion)
        project.version = newVersion

        println("Set version for ${project.name} to $newVersion")
    }
}

class PublishEasy: Plugin<Project> {
    override fun apply(project: Project) = project.run {
        readVersion()
        val updateTask = tasks.register("updateVersion") { updateVersion() }
        tasks.configureEach {
            if (listOf("publish", "publishToMavenLocal").contains(name)) {
                dependsOn(updateTask)
            }
        }
        configureGithubMaven(project)
    }

    private fun configureGithubMaven(project: Project) {
        project.plugins.apply("maven-publish")

        project.extensions.getByType(PublishingExtension::class.java).apply {
            repositories.maven {
                name = "GitHubPackages"
                url = project.uri("https://maven.pkg.github.com/shibasis0801/flatInvoker")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: "GITHUB_ACTOR"
                    password = System.getenv("GITHUB_TOKEN") ?: "GITHUB_TOKEN"
                }
            }

            publications.create("GithubMaven", MavenPublication::class.java).apply {
                groupId = "dev.shibasis"
                artifactId = project.name
                version = project.version.toString()

                when {
                    project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") -> {
                        from(project.components["kotlin"])
                    }
                    project.plugins.hasPlugin("java") -> {
                        from(project.components["java"])
                    }
                    else -> {
                        throw GradleException("Unsupported project type for publishing")
                    }
                }
            }
        }
    }
}
