package dev.shibasis.dependeasy.process

import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

internal fun Project.command(name: String, executable: String, arguments: Array<out String>,
                             configuration: CommandTask.() -> Unit) = tasks.register<CommandTask>(name) {
    group = "dependeasy"
    workingDirectory.set(layout.projectDirectory)
    this.executable.set(executable)
    this.arguments.set(arguments.toList())
    toolVersion.set(providers.exec { commandLine(executable, "--version") }.standardOutput.asText.map(String::trim))
    configuration()
}
