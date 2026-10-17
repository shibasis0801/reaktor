package dev.shibasis.dependeasy.repositories

import dev.shibasis.dependeasy.configuration.FrameworkOptions
import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.model.BuildOption
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.kotlin.dsl.maven

internal fun dependencyRepositories(project: Project, configure: RepositoryHandler.() -> Unit) {
    val providers = project.rootProject.providers
    val prefix = project.rootProject.name.lowercase()
    val username = project.option(FrameworkOptions.GitHubPackagesUsername).filter(String::isNotBlank)
        .orElse(project.option(BuildOption.string("$prefix.githubPackagesUsername")).filter(String::isNotBlank))
        .orElse(providers.environmentVariable("GITHUB_PACKAGES_USERNAME"))
        .orElse(providers.environmentVariable("GITHUB_ACTOR"))
        .orElse("shibasis0801")
    val token = project.option(FrameworkOptions.GitHubPackagesToken).filter(String::isNotBlank)
        .orElse(project.option(BuildOption.string("$prefix.githubPackagesToken")).filter(String::isNotBlank))
        .orElse(providers.environmentVariable("GITHUB_PACKAGES_TOKEN"))
        .orElse(providers.environmentVariable("GITHUB_TOKEN"))
    val local = project.option(FrameworkOptions.MavenLocal)
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
