package dev.shibasis.dependeasy.plugins

import org.gradle.api.Plugin
import org.gradle.api.Project

private fun Project.composePlugins() {
    pluginManager.apply("org.jetbrains.compose")
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
}

class ComposeLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(LibraryPlugin::class.java)
        project.composePlugins()
    }
}

class ComposeApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(ApplicationPlugin::class.java)
        DependeasyExtension.create(project)
        project.composePlugins()
    }
}

class BrowserPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(PipelinePlugin::class.java)
        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.composePlugins()
    }
}

class ComposeJvmPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(JvmPlugin::class.java)
        project.composePlugins()
    }
}
