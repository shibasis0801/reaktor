package dev.shibasis.dependeasy.plugins

import org.gradle.api.Plugin
import org.gradle.api.Project

class WebAppPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("base")
    }
}

class LoadTestsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("base")
    }
}
