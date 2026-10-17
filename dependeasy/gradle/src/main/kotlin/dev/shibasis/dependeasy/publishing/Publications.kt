package dev.shibasis.dependeasy.publishing

import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.model.BuildOption
import org.gradle.api.Project

class Publications internal constructor(private val project: Project) {
    var group = PublicationDefaults.Group
    var version = project.option(BuildOption.string("${project.rootProject.name}Version", PublicationDefaults.Version))
    private val repositories = mutableListOf<PublicationRepository>()

    fun githubPackages(repository: String = PublicationDefaults.Repository,
                       username: String = "USERNAME", token: String = "TOKEN") =
        maven("GitHubPackages", "https://maven.pkg.github.com/$repository", username, token)

    fun maven(name: String, url: String, username: String? = null, password: String? = null) {
        repositories.add(PublicationRepository(name, url, username, password))
    }

    internal fun install() = PublishingKernel(project, group, version, repositories).install()
}

internal data class PublicationRepository(
    val name: String, val url: String, val username: String?, val password: String?,
)
