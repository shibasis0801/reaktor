package dev.shibasis.dependeasy.workspace

import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.register

/** Gradle owns composite identity; machine adapters consume this portable directory map. */
@CacheableTask
abstract class BuildLayoutTask : DefaultTask() {
    @get:Input abstract val directories: MapProperty<String, String>
    @get:Input abstract val taskFamilies: MapProperty<String, String>
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction fun write() {
        val report = buildJsonObject {
            put("schemaVersion", 1)
            putJsonObject("builds") { directories.get().toSortedMap().forEach { (name, path) -> put(name, path) } }
            putJsonObject("taskFamilies") { taskFamilies.get().toSortedMap().forEach { (task, family) -> put(task, family) } }
        }
        outputFile.get().asFile.apply { parentFile.mkdirs(); writeText(report.toString() + "\n") }
    }
}

internal fun Project.reportBuildLayout() = tasks.register<BuildLayoutTask>("reportBuildLayout") {
    group = "dependeasy"
    description = "Describe the composite once for IDE and worker artifact ownership"
    val root = rootDir.canonicalFile
    directories.set(mapOf(rootProject.name to ".") + gradle.includedBuilds.associate {
        it.name to it.projectDir.canonicalFile.relativeTo(root).invariantSeparatorsPath
    })
    taskFamilies.convention(emptyMap())
    outputFile.set(layout.buildDirectory.file("dependeasy/build-layout.json"))
}

internal fun Project.declareArtifactFamily(task: String, family: String) {
    val path = if (this.path == ":") ":$task" else "${this.path}:$task"
    rootProject.tasks.named("reportBuildLayout", BuildLayoutTask::class.java).configure {
        taskFamilies.put(path, family)
    }
}
