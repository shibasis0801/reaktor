package dev.shibasis.dependeasy.repositories

import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.kotlin.dsl.maven

internal fun dependencyRepositories(project: Project, configure: RepositoryHandler.() -> Unit) {
    val providers = project.rootProject.providers
    val prefix = project.rootProject.name.lowercase()
    val username = providers.gradleProperty("dependeasy.githubPackagesUsername")
        .orElse(providers.gradleProperty("$prefix.githubPackagesUsername"))
        .orElse(providers.environmentVariable("GITHUB_PACKAGES_USERNAME"))
        .orElse(providers.environmentVariable("GITHUB_ACTOR"))
        .orElse("shibasis0801")
    val token = providers.gradleProperty("dependeasy.githubPackagesToken")
        .orElse(providers.gradleProperty("$prefix.githubPackagesToken"))
        .orElse(providers.environmentVariable("GITHUB_PACKAGES_TOKEN"))
        .orElse(providers.environmentVariable("GITHUB_TOKEN"))
    val local = providers.gradleProperty("dependeasy.useMavenLocal").map(String::toBoolean).orElse(false)
    project.allprojects {
        repositories.apply {
            if (local.get()) mavenLocal()
            mavenCentral()
            google()
            maven("https://jitpack.io") {
                content { includeGroupByRegex("com\\.github\\..*") }
            }
            maven("https://maven.pkg.github.com/shibasis0801/reaktor") {
                content {
                    includeGroupByRegex("dev\\.shibasis(\\..*)?")
                    includeGroup("reaktor")
                }
                token.orNull?.takeIf(String::isNotBlank)?.let { password ->
                    credentials {
                        this.username = username.get()
                        this.password = password
                    }
                }
            }
            configure()
        }
    }
}
