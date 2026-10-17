package dev.shibasis.dependeasy.plugins

import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.repositories.dependencyRepositories
import dev.shibasis.dependeasy.verification.physicalDevicesOnly
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.WorkspaceScope
import dev.shibasis.dependeasy.settings.NativeBootstrap
import dev.shibasis.dependeasy.web.installKotlinPnpmBridge
import org.gradle.api.Plugin
import org.gradle.api.Project

class WorkspacePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project == project.rootProject) { "The workspace plugin belongs to the root project" }
        configureWorkspaceIde(project)
        Reaktor.check(project)
        dependencyRepositories(project) {}
        physicalDevicesOnly(project)
        NativeBootstrap(project).also { project.extensions.add("nativeTools", it); it.register() }
        installKotlinPnpmBridge(project)
        project.extensions.add("workspace", WorkspaceScope(project, BuildWorkspace.get(project)))
    }
}
