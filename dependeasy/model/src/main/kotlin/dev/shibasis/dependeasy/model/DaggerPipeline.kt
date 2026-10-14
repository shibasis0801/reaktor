package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable data class DaggerPipelineContract(
    val schema: Int = 1,
    val build: String,
    val engineVersion: String,
    val browserImage: String,
    val environment: Map<String, String>,
    val toolchain: DaggerToolchainContract,
    val targets: List<Node>,
)
