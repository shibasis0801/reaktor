package dev.shibasis.dependeasy.components

import com.android.build.api.dsl.ApplicationExtension
import dev.shibasis.dependeasy.android.defaults
import dev.shibasis.dependeasy.common.dependencyNotation
import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.plugins.ApplicationPlugin
import dev.shibasis.dependeasy.workspace.WorkspaceDsl
import dev.shibasis.dependeasy.workspace.declarationSite
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

fun Project.androidApp(applicationId: String, versionCode: Int = 1, versionName: String = "1.0",
    debug: AndroidBuild = AndroidBuild(), release: AndroidBuild = AndroidBuild(),
    content: AndroidAppScope.() -> Unit = {}) {
    require(plugins.hasPlugin(ApplicationPlugin::class.java)) { "Apply dev.shibasis.dependeasy.application" }
    val spec = AndroidAppSpec(applicationId, versionCode, versionName,
        debug.copy(rules = debug.rules.toList()), release.copy(rules = release.rules.toList()))
    declarationSite()?.let { extensions.add(SourceSite::class.java, "dependeasyComponentDeclaration", it) }
    extensions.configure<ApplicationExtension> {
        defaults(spec.applicationId)
        defaultConfig {
            this.versionCode = spec.versionCode
            this.versionName = spec.versionName
        }
        listOf("debug" to spec.debug, "release" to spec.release).forEach { (name, settings) ->
            val signing = settings.signing?.let { key ->
                (signingConfigs.findByName(name) ?: signingConfigs.create(name)).apply {
                    storeFile = rootProject.file(option(key.keystore).get())
                    keyAlias = key.alias
                    val value = option(key.password).get()
                    storePassword = value
                    keyPassword = value
                }
            }
            buildTypes.getByName(name).apply {
                settings.applicationIdSuffix?.takeIf(String::isNotBlank)?.let { applicationIdSuffix = it }
                signing?.let { signingConfig = it }
                if (settings.rules.isNotEmpty()) {
                    isMinifyEnabled = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), *settings.rules.toTypedArray())
                }
            }
        }
    }
    AndroidAppScope(this).apply(content)
    publishLibraryRoles(setOf(LibraryTarget.Android))
}

@WorkspaceDsl
class AndroidAppScope internal constructor(private val project: Project) {
    fun dependencies(content: AndroidAppDependencies.() -> Unit) { AndroidAppDependencies(project).content() }
}

@WorkspaceDsl
class AndroidAppDependencies internal constructor(private val project: Project) {
    fun implementation(dependency: Any, vararg others: Any) {
        (listOf(dependency) + others).forEach {
            project.dependencies.add("implementation", if (it is ModuleHandle) project.dependencyNotation(it) else it)
        }
    }
    fun platform(coordinate: String) = project.dependencies.platform(coordinate)
}
