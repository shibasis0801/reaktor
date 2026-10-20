package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

/** Kotlin source roots and their checkout directory, relative to the workspace root. */
@Serializable
data class IdentitySource(val name: String, val directory: String, val roots: List<String>) {
    init {
        require(name.isNotBlank() && directory.isNotBlank() && roots.isNotEmpty() && roots.all(String::isNotBlank)) {
            "An identity source needs its name, checkout and Kotlin roots"
        }
    }
}

/** The property name and checkout supplying a git revision. */
@Serializable
data class IdentityRevision(val name: String, val directory: String) {
    init { require(name.isNotBlank() && directory.isNotBlank()) { "An identity revision needs its name and checkout" } }
}
