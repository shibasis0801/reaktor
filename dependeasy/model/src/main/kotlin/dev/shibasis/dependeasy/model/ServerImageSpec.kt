package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
data class ServerImageSpec(val jar: String, val repository: String, val dockerHost: String, val javaVersion: Int,
    val builder: String, val runImage: String, val tool: ToolRef) {
    init {
        require(repository.matches(Regex("[a-z0-9][a-z0-9./_-]+"))) { "Registry declaration must be an untagged repository" }
        require(java.net.URI(dockerHost).scheme == "unix") { "Buildpack requires the execution host's local Docker socket" }
        require(javaVersion > 0)
        require(listOf(builder, runImage).all { it.matches(Regex("[a-z0-9][a-z0-9./_-]+@sha256:[0-9a-f]{64}")) }) {
            "Buildpack base images must be pinned by manifest digest"
        }
    }
}

@Serializable
data class BuiltServerImage(val configDigest: String, val reference: String, val jarDigest: String)

@Serializable
data class PublishedServerImage(val image: String, val configDigest: String, val jarDigest: String)
