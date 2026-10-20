package dev.shibasis.dependeasy.benchmark

import dev.shibasis.dependeasy.model.BuildOption
import dev.shibasis.dependeasy.configuration.option
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test

internal class TestProfileKernel(private val project: Project) {
    fun install(output: String) {
        val enabled = project.option(BuildOption.environment("FLAMECHART")).map { it == "true" }.orElse(false)
        val library = ProfilerTools(project).library
        val destination = project.layout.projectDirectory.file(output)
        project.tasks.withType(Test::class.java).configureEach {
            jvmArgumentProviders.add(CpuAgentArguments(library, destination.asFile.absolutePath, enabled))
            doFirst { if (enabled.get()) destination.asFile.parentFile.mkdirs() }
        }
    }
}
