package dev.shibasis.dependeasy.plugins

import dev.shibasis.dependeasy.settings.pluginToolchains
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

class SettingsPlugin : Plugin<Settings> {
    override fun apply(target: Settings) {
        target.pluginManagement.repositories.maven {
            url = java.net.URI(dev.shibasis.dependeasy.toolchain.BuildLibraries.GradleToolingRepository)
            content { includeModule("org.gradle", "gradle-tooling-api") }
        }
        target.pluginToolchains()
    }
}
