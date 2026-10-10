package dev.shibasis.dependeasy.tasks

import com.android.build.api.attributes.BuildTypeAttr
import dev.shibasis.dependeasy.native.AndroidPrefabConfiguration
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

internal fun Project.registerPrefabTask(
    prefab: AndroidPrefabConfiguration,
): TaskProvider<ExtractPrefabTask> {
    val taskName = "extract${prefab.moduleName.replaceFirstChar(Char::uppercaseChar)}Prefab"
    val existing = tasks.findByName(taskName)
    if (existing != null) {
        @Suppress("UNCHECKED_CAST")
        return tasks.named(taskName) as TaskProvider<ExtractPrefabTask>
    }
    val aar = configurations.detachedConfiguration(dependencies.create(prefab.dependencyNotation)).apply {
        isTransitive = false
        attributes.attribute(BuildTypeAttr.ATTRIBUTE, objects.named(BuildTypeAttr::class.java, "release"))
    }
    return tasks.register<ExtractPrefabTask>(taskName) {
        group = "reaktor"
        archiveFiles.from(aar)
        moduleName.set(prefab.moduleName)
        outputDirectory.set(layout.buildDirectory.dir("dependeasy/prefab/${prefab.moduleName}"))
    }
}

internal fun KotlinCMakeTask.includePrefabs(
    prefabs: List<Pair<AndroidPrefabConfiguration, TaskProvider<ExtractPrefabTask>>>,
) {
    dependsOn(prefabs.map { it.second })
    prefabs.forEach { (prefab, task) ->
        sourceFiles.from(task.flatMap { it.outputDirectory })
        configureArguments.add(task.flatMap { it.outputDirectory }.map { directory ->
            "-D${prefab.cmakeVariable}=${directory.asFile.absolutePath}"
        })
    }
}
