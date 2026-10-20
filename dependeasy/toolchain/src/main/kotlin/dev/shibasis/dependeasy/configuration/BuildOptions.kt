package dev.shibasis.dependeasy.configuration

import dev.shibasis.dependeasy.model.*
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.util.Properties

data class ResolvedOption<T : Any>(val value: T, val evidence: OptionValue) : java.io.Serializable

fun <T : Any> Project.resolve(option: BuildOption<T>): Provider<ResolvedOption<T>> =
    resolve(providers, rootProject.layout.projectDirectory, gradle, option).also { resolved ->
        recordBuildOption(path, option, resolved)
    }

fun <T : Any> Settings.resolve(option: BuildOption<T>): Provider<ResolvedOption<T>> =
    resolve(providers, layout.settingsDirectory, gradle, option).also { resolved ->
        gradle.rootProject { recordBuildOption("settings", option, resolved) }
    }

@Suppress("UNCHECKED_CAST")
fun Project.resolvedBuildOptions(): ListProperty<Map<String, String>> {
    val root = rootProject
    val key = "dependeasyResolvedBuildOptions"
    return (root.extensions.findByName(key) as? ListProperty<Map<String, String>>)
        ?: (root.objects.listProperty(Map::class.java) as ListProperty<Map<String, String>>).convention(emptyList()).also {
            root.extensions.add(key, it)
        }
}

private fun <T : Any> Project.recordBuildOption(scope: String, option: BuildOption<T>, resolved: Provider<ResolvedOption<T>>) {
    fun fields(value: OptionValue) =
        mapOf("scope" to scope, "name" to value.name, "type" to value.type.name,
            "value" to value.value, "source" to value.source.name, "sensitive" to value.sensitive.toString()) +
            listOfNotNull(value.file?.let { "file" to it },
                option.takeIf { OptionSource.Environment in it.resolutionOrder }?.let { "environment" to (it.environment ?: "ORG_GRADLE_PROJECT_${it.name}") }).toMap()
    resolvedBuildOptions().addAll(resolved.map { result ->
        val value = result.evidence
        listOf(fields(value))
    }.orElse(listOf(fields(OptionValue(option.name, option.type, "<absent>", OptionSource.Absent, sensitive = option.sensitive)))))
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> resolve(providers: ProviderFactory, root: Directory, gradle: Gradle,
    option: BuildOption<T>): Provider<ResolvedOption<T>> = providers.of(BuildOptionSource::class.java) {
    parameters.option.set(option)
    parameters.root.set(root)
    parameters.userHome.set(gradle.gradleUserHomeDir)
    parameters.commandLine.set(gradle.startParameter.projectProperties.filterKeys { it == option.name })
}.map { it as ResolvedOption<T> }

abstract class BuildOptionSource : ValueSource<ResolvedOption<Any>, BuildOptionSource.Parameters>, org.gradle.api.Describable {
    override fun getDisplayName(): String = "Build option '${parameters.option.get().name}'"
    interface Parameters : ValueSourceParameters {
        val option: Property<BuildOption<*>>
        val root: DirectoryProperty
        val userHome: DirectoryProperty
        val commandLine: MapProperty<String, String>
    }

    override fun obtain(): ResolvedOption<Any>? {
        val option = parameters.option.get()
        for (source in option.resolutionOrder) {
            val file = when (source) {
                OptionSource.UserGradleProperties -> parameters.userHome.file("gradle.properties").get().asFile
                OptionSource.RootGradleProperties -> parameters.root.file("gradle.properties").get().asFile
                OptionSource.LocalProperties -> parameters.root.file("local.properties").get().asFile
                else -> null
            }
            val text = when (source) {
                OptionSource.CommandLine -> parameters.commandLine.get()[option.name]
                OptionSource.SystemProperty -> System.getProperty("org.gradle.project.${option.name}")
                OptionSource.JvmSystemProperty -> System.getProperty(option.name)
                OptionSource.Environment -> System.getenv(option.environment ?: "ORG_GRADLE_PROJECT_${option.name}")
                OptionSource.UserGradleProperties, OptionSource.RootGradleProperties, OptionSource.LocalProperties ->
                    file?.takeIf { it.isFile }?.let { Properties().apply { load(it.readText().reader()) }.getProperty(option.name) }
                OptionSource.Default -> option.default.toString()
                OptionSource.XcodeRelease, OptionSource.Absent -> null
            } ?: continue
            val value = option.parse(text)
            val path = file?.let { if (source == OptionSource.UserGradleProperties) it.absolutePath else it.name }
            return ResolvedOption(value, OptionValue(option.name, option.type,
                if (option.sensitive) "<redacted>" else value.toString(), source, path, option.sensitive,
                option.takeIf { OptionSource.Environment in it.resolutionOrder }?.let { it.environment ?: "ORG_GRADLE_PROJECT_${it.name}" }))
        }
        return null
    }
}

fun <T : Any> Project.option(option: BuildOption<T>): Provider<T> = resolve(option).map { it.value }

fun <T : Any> Settings.option(option: BuildOption<T>): Provider<T> =
    resolve(option).map { it.value }

fun <T : Any> Project.executionOption(option: BuildOption<T>): Provider<T> =
    resolve(providers, rootProject.layout.projectDirectory, gradle, option).map { it.value }
