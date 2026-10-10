package dev.shibasis.dependeasy.benchmark

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test

internal class TestProfileKernel(private val project: Project) {
    fun install(output: String) {
        val enabled = project.providers.environmentVariable("FLAMECHART").map { it == "true" }.orElse(false)
        val library = ProfilerTools(project).library.zip(enabled) { path, active -> if (active) path else "" }
        val destination = project.layout.projectDirectory.file(output)
        project.tasks.withType(Test::class.java).configureEach {
            jvmArgumentProviders.add(CpuAgentArguments(library, destination.asFile.absolutePath))
            doFirst { if (enabled.get()) destination.asFile.parentFile.mkdirs() }
        }
    }
}
