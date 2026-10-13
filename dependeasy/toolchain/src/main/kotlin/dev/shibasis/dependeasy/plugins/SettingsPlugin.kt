package dev.shibasis.dependeasy.plugins

import dev.shibasis.dependeasy.settings.pluginToolchains
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

class SettingsPlugin : Plugin<Settings> {
    override fun apply(target: Settings) {
        target.pluginToolchains()
    }
}
