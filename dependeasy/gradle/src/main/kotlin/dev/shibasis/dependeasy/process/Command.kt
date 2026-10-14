package dev.shibasis.dependeasy.process

import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

internal fun Project.command(name: String, tool: dev.shibasis.dependeasy.toolchain.InstalledTool, arguments: Array<out String>,
                             configuration: ProcessTask.() -> Unit) = tasks.register<ProcessTask>(name) {
    group = "dependeasy"
    workingDirectory.set(layout.projectDirectory)
    dependsOn(tool.installation)
    this.executable.set(tool.executable.map { it.asFile.absolutePath })
    environment.set(tool.environment)
    inputs.files(tool.executable)
    this.arguments.set(arguments.toList())
    toolVersion.set(tool.handle.version)
    configuration()
}
