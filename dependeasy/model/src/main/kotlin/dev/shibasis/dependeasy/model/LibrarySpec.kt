package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

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
data class LibrarySpec(val namespace: String, val targets: Set<LibraryTarget>) {
    init {
        require(namespace.isNotBlank()) { "A library needs its namespace" }
        require(targets.isNotEmpty()) { "A library needs at least one target" }
    }
}
