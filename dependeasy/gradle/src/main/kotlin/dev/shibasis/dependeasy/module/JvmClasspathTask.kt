package dev.shibasis.dependeasy.module

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Classpath files contain paths in this checkout")
abstract class JvmClasspathTask : DefaultTask() {
    @get:Classpath abstract val classpath: ConfigurableFileCollection
    @get:OutputFile abstract val destination: RegularFileProperty
    @get:Input val paths: List<String> get() = classpath.files.map { it.absolutePath }

    @TaskAction fun write() {
        destination.get().asFile.apply {
            parentFile.mkdirs()
            writeText(paths.joinToString("\n"))
        }
    }
}

internal fun Project.jvmClasspath(name: String, output: String, compilation: String): TaskProvider<JvmClasspathTask> {
    val execution = jvmExecution(compilation)
    return tasks.register<JvmClasspathTask>(name) {
        dependsOn(execution.classes)
        classpath.from(execution.classpath)
        destination.set(layout.buildDirectory.file(output))
    }
}
