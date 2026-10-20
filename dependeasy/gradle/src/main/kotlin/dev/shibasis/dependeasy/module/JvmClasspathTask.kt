package dev.shibasis.dependeasy.module

import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.CompilationRef
import dev.shibasis.dependeasy.model.KernelSpec
import dev.shibasis.dependeasy.model.OutputKind
import dev.shibasis.dependeasy.process.KernelSpecOwner
import org.gradle.api.provider.Property
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
abstract class JvmClasspathTask : DefaultTask(), KernelSpecOwner {
    @get:Input abstract val compilation: Property<String>
    init { compilation.convention(CompilationRef.Test.name) }
    override val kernelSpec get() = KernelSpec.JvmClasspath(CompilationRef(compilation.get()))
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
    return tasks.register<JvmClasspathTask>(name) {
        val execution = project.jvmExecution(compilation)
        this.compilation.set(compilation)
        dependsOn(execution.classes)
        classpath.from(execution.classpath)
        destination.set(layout.buildDirectory.file(output))
    }.also { node(it, OutputKind.GeneratedContract) }
}
