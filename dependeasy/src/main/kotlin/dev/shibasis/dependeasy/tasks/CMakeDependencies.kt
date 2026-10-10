package dev.shibasis.dependeasy.tasks

import dev.shibasis.dependeasy.native.NativeProjectDependency
import dev.shibasis.dependeasy.native.nativeBuildDirectory
import org.gradle.api.tasks.TaskProvider

internal fun KotlinCMakeTask.includeNativeDependencies(
    dependencies: List<NativeProjectDependency>,
    tasks: List<Pair<NativeProjectDependency, TaskProvider<KotlinCMakeTask>>>,
    platform: CmakePlatform,
) {
    dependsOn(tasks.map { it.second })
    tasks.forEach { (_, task) ->
        sourceFiles.from(task.flatMap { it.buildDirectory }.map { directory ->
            directory.asFileTree.matching { include("**/*.a", "**/*.so") }
        })
    }
    dependencies.forEachIndexed { index, dependency ->
        configureArguments.add("-DREAKTOR_NATIVE_DEPENDENCY_INCLUDE_DIRS_$index=${dependency.darwinIncludeDirs.joinToString(";")}")
    }
    if (dependencies.isNotEmpty()) {
        configureArguments.addAll(listOf(
            "-DREAKTOR_NATIVE_DEPENDENCY_PROJECTS=${dependencies.joinToString(";") { it.project.name }}",
            "-DREAKTOR_NATIVE_DEPENDENCY_SOURCE_DIRS=${dependencies.joinToString(";") { it.sourceDirectory.absolutePath }}",
            "-DREAKTOR_NATIVE_DEPENDENCY_TARGETS=${dependencies.joinToString(";") { it.libraryName }}",
        ))
    }
    if (tasks.isNotEmpty()) {
        val suffix = if (platform is CmakePlatform.Android) "so" else "a"
        configureArguments.add("-DREAKTOR_NATIVE_DEPENDENCY_LIBRARY_FILES=" + tasks.joinToString(";") { (dependency, _) ->
            dependency.project.nativeBuildDirectory(platform.variant).resolve("lib${dependency.libraryName}.$suffix").absolutePath
        })
    }
}
