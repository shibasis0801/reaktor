package dev.shibasis.dependeasy.darwin

import dev.shibasis.dependeasy.process.CommandTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

class SwiftPackagePipeline internal constructor(private val project: Project, private val name: String, directory: Any) {
    private val directory = project.file(directory)

    fun build(configuration: String = "release"): TaskProvider<CommandTask> =
        project.tasks.register<CommandTask>("${name}SwiftBuild") {
            group = "dependeasy"
            workingDirectory.set(this@SwiftPackagePipeline.directory)
            executable.set("swift")
            arguments.set(listOf("build", "--package-path", this@SwiftPackagePipeline.directory.absolutePath, "--configuration", configuration, "--disable-automatic-resolution"))
            toolVersion.set(project.providers.exec { commandLine("swift", "--version") }.standardOutput.asText.map { it.trim() })
            sourceFiles.from(project.fileTree(directory) {
                exclude(".build/**", ".swiftpm/**", ".git/**")
            })
            outputDirectories.from(directory.resolve(".build"))
        }
}
