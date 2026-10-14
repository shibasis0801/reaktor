package dev.shibasis.dependeasy.tasks

import com.android.build.api.attributes.BuildTypeAttr
import dev.shibasis.dependeasy.native.AndroidPrefabConfiguration
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal fun Project.registerPrefabTask(
    prefab: AndroidPrefabConfiguration,
): TaskProvider<ExtractPrefabTask> {
    val taskName = "extract${prefab.moduleName.replaceFirstChar(Char::uppercaseChar)}Prefab"
    val aar = configurations.detachedConfiguration(dependencies.create(prefab.dependencyNotation)).apply {
        isTransitive = false
        attributes.attribute(BuildTypeAttr.ATTRIBUTE, objects.named(BuildTypeAttr::class.java, "release"))
    }
    return registeredTask(ExtractPrefabTask::class.java, "${ExtractPrefabTask::class.java.name}:${prefab.moduleName}") { tasks.register<ExtractPrefabTask>(taskName) {
        group = "reaktor"
        archiveFiles.from(aar)
        moduleName.set(prefab.moduleName)
        expectedChecksum.set(prefab.checksum)
        outputDirectory.set(layout.buildDirectory.dir("dependeasy/prefab/${prefab.moduleName}"))
    } }
}

internal fun KotlinCMakeTask.includePrefabs(
    prefabs: List<Pair<AndroidPrefabConfiguration, TaskProvider<ExtractPrefabTask>>>,
) {
    dependsOn(prefabs.map { it.second })
    prefabs.forEach { (prefab, task) ->
        sourceFiles.from(task.flatMap { it.outputDirectory })
        prefabDirectories.put(prefab.cmakeVariable, task.flatMap { it.outputDirectory }.map { it.asFile.absolutePath })
        prefabTargets.put(prefab.cmakeVariable, prefab.target)
    }
}
