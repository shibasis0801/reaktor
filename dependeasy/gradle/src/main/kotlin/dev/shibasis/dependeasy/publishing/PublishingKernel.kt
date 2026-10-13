package dev.shibasis.dependeasy.publishing

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

internal class PublishingKernel(
    private val project: Project,
    private val group: String,
    private val version: Provider<String>,
    private val repositories: List<PublicationRepository>,
    private val builds: List<String>,
) {
    fun install() {
        require(project == project.rootProject) { "Declare publications at the workspace root" }
        project.subprojects.forEach { module ->
            module.group = group
            module.version = version.get()
            module.pluginManager.apply("maven-publish")
            val publishing = module.extensions.getByType<PublishingExtension>()
            repositories.forEach { repository ->
                publishing.repositories.maven {
                    name = repository.name
                    url = module.uri(repository.url)
                    if (repository.username != null && repository.password != null) {
                        credentials {
                            username = module.providers.environmentVariable(repository.username).orNull
                            password = module.providers.environmentVariable(repository.password).orNull
                        }
                    }
                }
            }
        }
        aggregate("publishToGithubPackages", "publish")
        aggregate("publishToMavenLocal", "publishToMavenLocal")
    }

    private fun aggregate(name: String, task: String) {
        project.tasks.register(name) {
            group = "publishing"
            description = "Publish the workspace and declared included builds"
            dependsOn(project.subprojects.map { "${it.path}:$task" })
            dependsOn(builds.map { project.gradle.includedBuild(it).task(":$task") })
        }
    }
}
