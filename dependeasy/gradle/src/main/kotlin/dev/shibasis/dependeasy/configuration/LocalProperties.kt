package dev.shibasis.dependeasy.configuration

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.util.Properties
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

private fun Project.resolvedProperty(name: String): Provider<Pair<String, String>> {
    val commandLine = gradle.startParameter.projectProperties[name]
    fun propertyFile(path: String, source: String) = providers.fileContents(rootProject.layout.projectDirectory.file(path))
        .asText.orElse("").map { text -> Properties().apply { load(text.reader()) } }
        .filter { it.containsKey(name) }.map { it.getProperty(name) to source }
    val source = providers.systemProperty("org.gradle.project.$name").map { "system property" }
        .orElse(propertyFile(gradle.gradleUserHomeDir.resolve("gradle.properties").absolutePath, "Gradle user-home gradle.properties").map { it.second })
        .orElse(propertyFile("gradle.properties", "gradle.properties").map { it.second })
        .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_$name").map { "environment" })
        .orElse("Gradle property")
    return if (commandLine != null) providers.provider { commandLine to "command line" }
        else providers.gradleProperty(name).zip(source) { value, origin -> value to origin }
            .orElse(propertyFile("local.properties", "local.properties"))
            .orElse(providers.provider { "" to "default" })
}

internal fun Project.localProperty(name: String): Provider<String> = resolvedProperty(name).map { it.first }

internal fun Project.releaseProperty(name: String): Provider<String> {
    val effective = resolvedProperty(name).zip(providers.environmentVariable("CONFIGURATION").orElse("")) { value, configuration ->
        if (configuration == "Release") "false" to "Xcode CONFIGURATION=Release" else value
    }
    val resolvedEnabled = effective.map { (value, _) -> if (value.isEmpty()) false else value.toBooleanStrict() }
    val requirement = tasks.register<RequireDisabledPropertyTask>(
        "require${name.split('.', '-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }}DisabledForRelease"
    ) {
        optionName.set(name)
        optionEnabled.set(resolvedEnabled)
        source.set(effective.map { it.second })
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
    return resolvedEnabled.map(Boolean::toString)
}
