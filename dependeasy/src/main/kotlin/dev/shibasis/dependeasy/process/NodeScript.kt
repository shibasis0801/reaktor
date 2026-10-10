package dev.shibasis.dependeasy.process

import dev.shibasis.dependeasy.toolchain.JavaScriptTools
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal fun Project.nodeScript(name: String, script: Any, configure: CommandTask.() -> Unit): TaskProvider<CommandTask> {
    val tools = JavaScriptTools.get(this)
    val source = file(script)
    return tasks.register<CommandTask>(name) {
        dependsOn(tools.nodeSetup)
        workingDirectory.set(rootProject.projectDir)
        executable.set(tools.node.map { it.asFile.absolutePath })
        environment.put("PATH", tools.path)
        toolVersion.set("node ${ToolchainVersions.Node}")
        arguments.set(listOf(source.absolutePath))
        sourceFiles.from(source)
        configure()
    }
}
