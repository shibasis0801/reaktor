package dev.shibasis.dependeasy.darwin

import dev.shibasis.dependeasy.process.ProcessTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

class SwiftPackagePipeline internal constructor(private val project: Project, private val name: String, directory: Any) {
    private val directory = project.file(directory)

    fun build(configuration: String = "release"): TaskProvider<ProcessTask> {
        val tool = dev.shibasis.dependeasy.toolchain.NativeBuildTools.get(project).swift
        return project.tasks.register<ProcessTask>("${name}SwiftBuild") {
            group = "dependeasy"
            workingDirectory.set(this@SwiftPackagePipeline.directory)
            dependsOn(tool.installation)
            executionPlatform.set(dev.shibasis.dependeasy.model.HostPlatform.Macos)
            executable.set(tool.executable.map { it.asFile.absolutePath })
            arguments.set(listOf("build", "--package-path", this@SwiftPackagePipeline.directory.absolutePath, "--configuration", configuration, "--disable-automatic-resolution"))
            toolVersion.set(tool.handle.version)
            sourceFiles.from(project.fileTree(directory) {
                exclude(".build/**", ".swiftpm/**", ".git/**")
            })
            outputDirectories.from(directory.resolve(".build"))
        }
    }
}
