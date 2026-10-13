package dev.shibasis.dependeasy.publishing

import org.gradle.api.Project

class Publications internal constructor(private val project: Project) {
    var group = PublicationDefaults.Group
    var version = project.providers.gradleProperty("${project.rootProject.name}Version")
        .orElse(PublicationDefaults.Version)
    private val repositories = mutableListOf<PublicationRepository>()
    private val builds = mutableListOf<String>()

    fun githubPackages(repository: String = PublicationDefaults.Repository,
                       username: String = "USERNAME", token: String = "TOKEN") =
        maven("GitHubPackages", "https://maven.pkg.github.com/$repository", username, token)

    fun maven(name: String, url: String, username: String? = null, password: String? = null) {
        repositories.add(PublicationRepository(name, url, username, password))
    }

    fun includedBuild(vararg names: String) { builds.addAll(names) }

    internal fun install() = PublishingKernel(project, group, version, repositories, builds).install()
}

internal data class PublicationRepository(
    val name: String, val url: String, val username: String?, val password: String?,
)
