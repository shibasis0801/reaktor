package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ProcessorOption(val name: String) {
    init { require(name.isNotBlank() && '=' !in name) { "A processor option needs its wire name" } }
}

@Serializable
enum class LibraryTarget { Android, Apple, Web, Jvm }

object Targets {
    val Android = setOf(LibraryTarget.Android)
    val Apple = setOf(LibraryTarget.Apple)
    val Web = setOf(LibraryTarget.Web)
    val Jvm = setOf(LibraryTarget.Jvm)
    val All = LibraryTarget.entries.toSet()
}

@Serializable
data class Compose(val stability: String? = null, val resources: String? = null, val reports: Boolean = false) {
    init {
        require(stability == null || stability.isNotBlank()) { "Compose stability needs its authored input path" }
        require(resources == null || resources.isNotBlank()) { "Compose resources need their package name" }
    }
}

@Serializable
data class LibrarySpec(val namespace: String, val targets: Set<LibraryTarget>, val bytecode: Int, val webModule: String? = null, val ui: Compose? = null, val appleFrameworkExports: List<ModuleHandle>? = null, val sourceDirectories: Map<LibraryTarget, List<String>> = emptyMap(), val jvmNullChecks: Boolean = true) {
    init {
        require(namespace.isNotBlank()) { "A library needs its namespace" }
        require(targets.isNotEmpty()) { "A library needs at least one target" }
        require(sourceDirectories.keys.all { it in targets }) { "Sources need a declared library target" }
        require(sourceDirectories.values.flatten().all(String::isNotBlank)) { "Sources need their authored directory" }
        require(webModule == null || LibraryTarget.Web in targets) { "A web module needs the Web target" }
        require(webModule == null || webModule.isNotBlank()) { "A web module needs its package name" }
        require(appleFrameworkExports == null || LibraryTarget.Apple in targets) { "An Apple framework needs the Apple target" }
    }
}
