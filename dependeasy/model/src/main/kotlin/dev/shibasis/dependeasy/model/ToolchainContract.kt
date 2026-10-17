package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
data class DaggerToolchainContract(
    val schemaVersion: Int = 1,
    val javaImage: String,
    val gradleUrl: String,
    val gradleSha256: String,
    val gradleArchive: String,
    val androidSdk: AndroidSdkContract,
)

@Serializable
data class AndroidSdkContract(
    val directory: String,
    val commandLineTools: String,
    val downloadUrl: String,
    val sha256: String,
    val packages: List<String>,
)
