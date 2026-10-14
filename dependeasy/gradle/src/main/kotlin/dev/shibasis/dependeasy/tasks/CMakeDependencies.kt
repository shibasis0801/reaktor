package dev.shibasis.dependeasy.tasks

import dev.shibasis.dependeasy.native.NativeProjectDependency
import org.gradle.api.tasks.TaskProvider

internal fun KotlinCMakeTask.includeNativeDependencies(
    tasks: List<Pair<NativeProjectDependency, TaskProvider<KotlinCMakeTask>>>,
) {
    dependsOn(tasks.map { it.second })
    tasks.forEach { (dependency, task) ->
        dependencyManifests.from(task.flatMap { it.staticLibrariesManifest })
        dependencyIncludes.put(dependency.libraryName, dependency.darwinIncludeDirs)
        sourceFiles.from(task.map { producer -> producer.buildDirectory.get().asFileTree.matching { include("**/*.a", "**/*.so") } })
    }
}
