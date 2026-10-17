package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import dev.shibasis.dependeasy.web.PnpmWorkspace
import kotlinx.serialization.encodeToString
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

internal fun Project.externalCommand(name: String, call: ExternalCall, removal: String, effect: Effect, run: Run): dev.shibasis.dependeasy.handles.NodeHandle {
    val bash = if (call is ExternalCall.BashScript) dev.shibasis.dependeasy.toolchain.NativeBuildTools.get(this).bash else null
    val packages = if (call is ExternalCall.Package) PnpmWorkspace.get(this) else null
    return tasks.register<WorkspaceCommandTask>(name) {
        group = "dependeasy"
        declaration.set(BuildModelReader.json.encodeToString(KernelSpec.ExternalCommand(call, removal)))
        executionEffect.set(effect)
        executionPlatform.set(run.host.platform)
        when (call) {
            is ExternalCall.BashScript -> {
                require(!java.io.File(call.path).isAbsolute) { "A script needs a workspace-relative path" }
                val tool = requireNotNull(bash)
                workingDirectory.set(layout.projectDirectory)
                executable.set(tool.executable.map { it.asFile.absolutePath })
                arguments.set(listOf(call.path))
                environment.putAll(tool.environment)
                dependsOn(tool.installation)
                sourceFiles.from(layout.projectDirectory.file(call.path))
                toolVersion.set(tool.handle.version)
            }
            is ExternalCall.Fastlane -> {
                workingDirectory.set(layout.projectDirectory)
                executable.set(layout.projectDirectory.file("fastlane/run.sh").asFile.absolutePath)
                arguments.set(listOf(call.lane.platform, call.lane.name))
                toolVersion.set("fastlane contract")
                sourceFiles.from(fileTree("fastlane") { exclude("**/build/**", "**/vendor/**", "**/report.xml", "**/README.md") })
            }
            is ExternalCall.Package -> {
                require(!java.io.File(call.script.directory).isAbsolute) { "A package script needs a workspace-relative directory" }
                val workspace = requireNotNull(packages)
                workingDirectory.set(layout.projectDirectory.dir(call.script.directory))
                executable.set(workspace.tools.pnpm.map { it.asFile.absolutePath })
                arguments.set(listOf("run", call.script.name))
                environment.putAll(workspace.tools.environment)
                dependsOn(workspace.install)
                toolVersion.set("pnpm ${ToolchainVersions.Pnpm} / node ${ToolchainVersions.Node}")
                sourceFiles.from(layout.projectDirectory.file("${call.script.directory}/package.json"))
            }
        }
        run.timeoutMillis?.let { timeout.set(java.time.Duration.ofMillis(it)) }
    }.let { node(it) }
}
