package dev.shibasis.dependeasy.compose

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

internal fun Project.composeConfiguration(stability: Any?, reports: String?) {
    extensions.configure<ComposeCompilerGradlePluginExtension> {
        stability?.let { stabilityConfigurationFiles.add(layout.projectDirectory.file(file(it).absolutePath)) }
        if (reports != null && providers.gradleProperty(reports).orNull == "true") {
            reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
            metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
        }
    }
}
