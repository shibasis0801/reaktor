package dev.shibasis.dependeasy.plugins

import dev.shibasis.dependeasy.module.jvmConfiguration
import org.gradle.api.Plugin
import org.gradle.api.Project

class JvmPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(PipelinePlugin::class.java)
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")
        project.pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        project.pluginManager.apply("java-library")
        project.jvmConfiguration()
    }
}
