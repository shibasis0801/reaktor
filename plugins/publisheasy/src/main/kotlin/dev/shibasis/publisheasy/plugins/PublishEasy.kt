package dev.shibasis.publisheasy.plugins

import org.gradle.api.*
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.get
import org.gradle.plugin.devel.PluginDeclaration
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

fun Project.defaults() {
    val date = LocalDate.now()
    val year = date.year
    val dayOfYear = date.dayOfYear

    val time = LocalTime.now()
    val minute = time.hour * 60 + time.minute

    group = "dev.shibasis"
    version = "$year.$dayOfYear.$minute"
}

fun Project.githubPublication(id: String = name) {
    project.extensions.getByType(PublishingExtension::class.java).apply {
        publications.create(id.replace("-", ""), MavenPublication::class.java).apply {
            groupId = project.group.toString()
            artifactId = id
            version = project.version.toString()

            println("Shibasis: $groupId:$artifactId:$version")

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

class PublishEasy: Plugin<Project> {
    override fun apply(project: Project) = project.run {
        project.defaults()
        plugins.apply("maven-publish")
        project.extensions.getByType(PublishingExtension::class.java).apply {
            repositories.maven {
                name = "GitHubPackages"
                url = project.uri("https://maven.pkg.github.com/shibasis0801/flatInvoker")
                credentials {
                    username = System.getenv("USERNAME")
                    password = System.getenv("TOKEN")
                }
            }
        }

        githubPublication()
    }
}
