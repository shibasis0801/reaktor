package dev.shibasis.dependeasy.configuration

import dev.shibasis.dependeasy.model.*
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.Provider
import java.util.Properties

data class ResolvedOption<T : Any>(val value: T, val evidence: OptionValue) : java.io.Serializable

fun <T : Any> Project.resolve(option: BuildOption<T>): Provider<ResolvedOption<T>> =
    resolve(providers, rootProject.layout.projectDirectory, gradle, option)

fun <T : Any> Settings.resolve(option: BuildOption<T>): Provider<ResolvedOption<T>> =
    resolve(providers, layout.settingsDirectory, gradle, option)

private fun <T : Any> resolve(providers: ProviderFactory, root: Directory, gradle: Gradle,
    option: BuildOption<T>): Provider<ResolvedOption<T>> {
    fun parsed(text: String, source: OptionSource, file: String? = null): ResolvedOption<T> =
        option.parse(text).let { value -> ResolvedOption(value, OptionValue(option.name, option.type, value.toString(), source, file)) }
    fun propertyFile(path: String, source: OptionSource) = providers.fileContents(root.file(path))
        .asText.orElse("").map { text -> Properties().apply { load(text.reader()) } }
        .filter { it.containsKey(option.name) }.map { parsed(it.getProperty(option.name), source, path) }
    val empty = providers.provider<ResolvedOption<T>> { null }
    return option.resolutionOrder.fold(empty) { selected, source ->
        selected.orElse(when (source) {
            OptionSource.CommandLine -> gradle.startParameter.projectProperties[option.name]?.let { value ->
                providers.provider { parsed(value, source) }
            } ?: empty
            OptionSource.SystemProperty -> providers.systemProperty("org.gradle.project.${option.name}").map { parsed(it, source) }
            OptionSource.UserGradleProperties -> propertyFile(gradle.gradleUserHomeDir.resolve("gradle.properties").absolutePath, source)
            OptionSource.RootGradleProperties -> propertyFile("gradle.properties", source)
            OptionSource.Environment -> providers.environmentVariable(option.environment ?: "ORG_GRADLE_PROJECT_${option.name}").map { parsed(it, source) }
            OptionSource.LocalProperties -> propertyFile("local.properties", source)
            OptionSource.Default -> providers.provider { ResolvedOption(option.default,
                OptionValue(option.name, option.type, option.default.toString(), source)) }
            OptionSource.XcodeRelease -> empty
        })
    }
}

fun <T : Any> Project.option(option: BuildOption<T>): Provider<T> = resolve(option).map { it.value }

fun <T : Any> Settings.option(option: BuildOption<T>): Provider<T> = resolve(option).map { it.value }
