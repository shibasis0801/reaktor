package dev.shibasis.dependeasy.compose

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.model.BuildOption

internal fun Project.composeConfiguration(stability: Any?, reports: BuildOption<Boolean>?) {
    extensions.configure<ComposeCompilerGradlePluginExtension> {
        stability?.let { stabilityConfigurationFiles.add(layout.projectDirectory.file(file(it).absolutePath)) }
        if (reports != null && option(reports).get()) {
            reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
            metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
        }
    }
}
