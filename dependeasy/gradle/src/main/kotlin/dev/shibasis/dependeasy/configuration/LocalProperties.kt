package dev.shibasis.dependeasy.configuration

import dev.shibasis.dependeasy.model.*
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

internal fun Project.releaseProperty(option: BuildOption<Boolean>): Provider<Boolean> {
    val effective = resolve(option).zip(providers.environmentVariable("CONFIGURATION").orElse("")) { resolved, configuration ->
        if (configuration == "Release") ResolvedOption(false, resolved.evidence.copy(value = "false", source = OptionSource.XcodeRelease)) else resolved
    }
    val requirement = tasks.register<RequireDisabledPropertyTask>(
        "require${option.name.split('.', '-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }}DisabledForRelease"
    ) {
        optionName.set(option.name)
        optionEnabled.set(effective.map { it.value })
        source.set(effective.map { resolved -> resolved.evidence.file ?: when (resolved.evidence.source) {
            OptionSource.CommandLine -> "command line"
            OptionSource.Default -> "default"
            OptionSource.XcodeRelease -> "Xcode CONFIGURATION=Release"
            else -> resolved.evidence.source.name
        } })
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
        kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
            binaries.configureEach {
                if (buildType == NativeBuildType.RELEASE) linkTaskProvider.configure { dependsOn(requirement) }
            }
        }
        kotlin.targets.withType(KotlinAndroidTarget::class.java).configureEach {
            compilations.matching { it.name == "release" }.configureEach {
                compileTaskProvider.configure { dependsOn(requirement) }
            }
        }
    }
    return effective.map { it.value }
}
