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

fun Project.githubPublication(id: String = name) {
    project.extensions.getByType(PublishingExtension::class.java).apply {
        publications.create(id.replace("-", ""), MavenPublication::class.java).apply {
            groupId = "dev.shibasis"
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
    override fun apply(project: Project): Unit = project.run {
        group = "dev.shibasis"
        version = LocalDate.now().run { "$year.$month.$dayOfMonth" }

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
        if (!project.plugins.hasPlugin("java-gradle-plugin")) {
            githubPublication(name)
        }
    }
}
