package dev.shibasis.dependeasy.native

import dev.shibasis.dependeasy.plugins.DependeasyExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import java.io.File

data class NativeProjectDependency(
    val project: Project,
    val sourceDirectory: File,
    val libraryName: String,
    val darwinIncludeDirs: List<String>,
)

fun Project.nativeProjectDependencies(): List<NativeProjectDependency> {
    val visited = linkedSetOf(path)
    val result = mutableListOf<NativeProjectDependency>()

    fun collect(current: Project) {
        current.configurations
            .asSequence()
            .filterNot {
                val name = it.name.lowercase()
                "test" in name || "ksp" in name || "metadata" in name || "lint" in name
            }
            .flatMap { configuration ->
                configuration.dependencies
                    .withType(ProjectDependency::class.java)
                    .asSequence()
                    .map { dependency -> current.project(dependency.path) }
            }
            .distinctBy(Project::getPath)
            .forEach { dependencyProject ->
                if (!visited.add(dependencyProject.path)) return@forEach
                current.evaluationDependsOn(dependencyProject.path)
                val native = dependencyProject.nativeConfigurationOrNull
                    ?.takeIf { it.isEnabled }
                    ?: run {
                        collect(dependencyProject)
                        return@forEach
                    }
                result += NativeProjectDependency(
                    project = dependencyProject,
                    sourceDirectory = native.resolvedSourceDirectory,
                    libraryName = native.resolvedLibraryName,
                    darwinIncludeDirs = native.darwin.resolvedIncludeDirs,
                )
                collect(dependencyProject)
            }
    }

    collect(this)
    return result
}
