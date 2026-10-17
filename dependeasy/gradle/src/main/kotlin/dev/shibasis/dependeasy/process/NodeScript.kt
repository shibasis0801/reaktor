package dev.shibasis.dependeasy.process

import dev.shibasis.dependeasy.web.NodeGenerator
import dev.shibasis.dependeasy.toolchain.JavaScriptTools
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal fun Project.nodeScript(name: String, script: Any, configure: ProcessTask.() -> Unit): TaskProvider<NodeGenerator> {
    val tools = JavaScriptTools.get(this)
    val source = file(script)
    return tasks.register<NodeGenerator>(name) {
        dependsOn(tools.nodeSetup)
        workingDirectory.set(rootProject.projectDir)
        executable.set(tools.node.map { it.asFile.absolutePath })
        environment.putAll(tools.environment)
        toolVersion.set("node ${ToolchainVersions.Node}")
        this.script.set(source)
        sourceFiles.from(source)
        configure()
    }
}
