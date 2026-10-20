package dev.shibasis.dependeasy.publishing

import dev.shibasis.dependeasy.model.BuildOption
import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.workspace.WorkspaceNodeTask
import dev.shibasis.dependeasy.workspace.declarationSite
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
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
                            username = module.option(BuildOption.environment(repository.username)).orNull
                            password = module.option(BuildOption.environment(repository.password, sensitive = true)).orNull
                        }
                    }
                }
            }
        }
        aggregate("publishToGithubPackages", Publication.Remote, PublishToMavenRepository::class.java)
        aggregate("publishToMavenLocal", Publication.Local, PublishToMavenLocal::class.java)
    }

    private fun aggregate(name: String, destination: Publication, type: Class<out org.gradle.api.Task>) {
        val site = project.declarationSite()
        val outputs = project.subprojects.map { module ->
            val completed = module.tasks.register<WorkspaceNodeTask>("complete${destination.name}Publication") {
                bind(ReleaseKernel)
                dependsOn(module.tasks.withType(type))
            }
            val output = OutputHandle(project.dependencies.project(mapOf("path" to module.path)),
                project.name, module.path, OutputKind.PublishedModules, listOf(destination.name))
            module.publishOutput(output, completed.flatMap { it.completionManifest }, completed, declaredAt = site)
            project.resolve(output)
        }
        project.tasks.register(name) {
            group = "publishing"
            description = "Publish the workspace modules"
            dependsOn(outputs)
        }
    }
}
