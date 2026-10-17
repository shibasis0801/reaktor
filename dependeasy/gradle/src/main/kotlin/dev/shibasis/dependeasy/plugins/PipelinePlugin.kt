package dev.shibasis.dependeasy.plugins

import org.gradle.api.Plugin
import org.gradle.api.Project
import dev.shibasis.dependeasy.web.installKotlinPnpmBridge
import dev.shibasis.dependeasy.verification.physicalDevicesOnly

/** Declarative pipelines for pnpm, SwiftPM and CMake without imposing Kotlin or Android. */
class PipelinePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project.extensions.findByType(DependeasyExtension::class.java) == null) {
            DependeasyExtension.create(project)
        }
        if (project == project.rootProject) {
            configureWorkspaceIde(project)
            dev.shibasis.dependeasy.handles.Reaktor.check(project)
            dev.shibasis.dependeasy.settings.NativeBootstrap(project).also {
                project.extensions.add("nativeTools", it)
                it.register()
            }
            dev.shibasis.dependeasy.workspace.BuildWorkspace.get(project)
            dev.shibasis.dependeasy.repositories.dependencyRepositories(project) {}
            physicalDevicesOnly(project)
            installKotlinPnpmBridge(project)
        }
    }
}
