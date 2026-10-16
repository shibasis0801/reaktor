package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.common.KotlinDependencies
import dev.shibasis.dependeasy.model.LibrarySpec
import dev.shibasis.dependeasy.model.LibraryTarget
import dev.shibasis.dependeasy.model.SourceSite
import dev.shibasis.dependeasy.workspace.declarationSite
import dev.shibasis.dependeasy.module.MultiplatformModule
import dev.shibasis.dependeasy.plugins.LibraryPlugin
import org.gradle.api.Project

@DslMarker
annotation class LibraryDsl

fun Project.library(namespace: String, targets: Set<LibraryTarget>, content: LibraryScope.() -> Unit = {}) {
    require(plugins.hasPlugin(LibraryPlugin::class.java)) { "Apply dev.shibasis.dependeasy.library" }
    val spec = LibrarySpec(namespace, targets.toSet())
    declarationSite()?.let { extensions.add(SourceSite::class.java, "dependeasyComponentDeclaration", it) }
    LibraryScope(spec).apply(content).bind(this)
}

@LibraryDsl
class LibraryScope internal constructor(private val spec: LibrarySpec) {
    private val dependencies = LibraryDependencies(spec)

    fun dependencies(content: LibraryDependencies.() -> Unit) { dependencies.apply(content) }

    internal fun bind(project: Project) {
        val module = MultiplatformModule(project, spec.namespace)
        module.common { dependencies { this@LibraryScope.dependencies.common.forEach { it(this) } } }
        spec.targets.forEach { target ->
            val configure: KotlinDependencies.() -> Unit = {
                this@LibraryScope.dependencies.platforms[target].orEmpty().forEach { it(this) }
            }
            when (target) {
                LibraryTarget.Android -> module.android { dependencies(configure) }
                LibraryTarget.Apple -> module.apple { dependencies(configure) }
                LibraryTarget.Web -> module.web { dependencies(configure) }
                LibraryTarget.Jvm -> module.jvm { dependencies(configure) }
            }
        }
    }
}

@LibraryDsl
class LibraryDependencies internal constructor(private val spec: LibrarySpec) {
    internal val common = mutableListOf<KotlinDependencies.() -> Unit>()
    internal val platforms = mutableMapOf<LibraryTarget, MutableList<KotlinDependencies.() -> Unit>>()

    fun common(content: KotlinDependencies.() -> Unit) { common.add(content) }
    fun android(content: KotlinDependencies.() -> Unit) = platform(LibraryTarget.Android, content)
    fun apple(content: KotlinDependencies.() -> Unit) = platform(LibraryTarget.Apple, content)
    fun web(content: KotlinDependencies.() -> Unit) = platform(LibraryTarget.Web, content)
    fun jvm(content: KotlinDependencies.() -> Unit) = platform(LibraryTarget.Jvm, content)

    private fun platform(target: LibraryTarget, content: KotlinDependencies.() -> Unit) {
        require(target in spec.targets) { "Library ${spec.namespace} does not declare the $target target" }
        platforms.getOrPut(target) { mutableListOf() }.add(content)
    }
}
