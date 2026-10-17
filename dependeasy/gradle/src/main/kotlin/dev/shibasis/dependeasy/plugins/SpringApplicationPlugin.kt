package dev.shibasis.dependeasy.plugins

import dev.shibasis.dependeasy.server.springDefaults
import org.gradle.api.Plugin
import org.gradle.api.Project

class SpringApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(ComposeJvmPlugin::class.java)
        project.pluginManager.apply("org.jetbrains.kotlin.plugin.spring")
        project.pluginManager.apply("org.springframework.boot")
        project.springDefaults()
    }
}
