package dev.shibasis.dependeasy.verification

import dev.shibasis.dependeasy.handles.NodeHandle
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.toolchain.JavaScriptTools
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

internal fun Project.devicePreparation(name: String, hook: DeviceHook): NodeHandle {
    val javascript = if (hook is DeviceHook.Node) JavaScriptTools.get(this) else null
    val script = when (hook) {
        is DeviceHook.Node -> hook.script
        is DeviceHook.PythonDevice -> hook.script
    }
    val platform = if (hook is DeviceHook.PythonDevice) HostPlatform.Macos else HostPlatform.Portable
    val task = tasks.register<ProcessTask>(name) {
        group = "verification"
        workingDirectory.set(rootProject.layout.projectDirectory)
        executionEffect.set(Effect.Verify)
        executionPlatform.set(platform)
        sourceFiles.from(rootProject.file(script))
        receiptFile.set(layout.buildDirectory.file("dependeasy/device/$name-preparation.txt"))
        if (javascript != null) {
            executable.set(javascript.node.map { it.asFile.absolutePath })
            environment.put("PATH", javascript.path)
            arguments.set(listOf(script, "beforeBuild"))
            toolVersion.set(ToolchainVersions.Node)
        } else {
            executable.set("/usr/bin/python3")
            arguments.set(listOf(script))
            toolVersion.set("macOS Python")
        }
        outputs.upToDateWhen { false }
    }
    BuildWorkspace.get(this).reference(this, task, null, HostRequirement(platform, ExecutionHost.Local))
    return node(task).also { preparation ->
        javascript?.let { preparation.after(rootProject.node(it.nodeSetup)) }
    }
}
