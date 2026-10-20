package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable data class AppleHostContract(
    val schema: Int = 1,
    val deploymentTarget: String,
    val swiftVersion: String,
    val team: String,
    val applicationTarget: String,
    val entryPoint: String = "",
    val versionOption: String = "",
    val numberOption: String = "",
    val developmentToolsOption: String = "",
    val compilationCaching: Boolean = false,
    val targets: List<AppleHostTarget>,
    val swiftPackages: Map<String, String>,
)

@Serializable data class AppleHostTarget(
    val name: String,
    val bundleId: String,
    val framework: String,
    val frameworkDirectory: String,
    val settingsPrefix: String,
    val adapters: List<String> = emptyList(),
    val backgroundTasks: List<String> = emptyList(),
    val linkFlags: List<String> = emptyList(),
) {
    init { require(settingsPrefix.matches(Regex("[A-Z][A-Z0-9_]*"))) { "Invalid Xcode settings prefix" } }
}

@Serializable data class ReleasePlan(
    val schema: Int = 1,
    val product: String,
    val versionName: String,
    val buildNumber: Int,
    val bundleId: String,
    val extensionBundleId: String? = null,
    val team: String,
    val artifacts: Map<String, String>,
    val commands: Map<String, ReleaseCommand>,
)

@Serializable data class ReleaseCommand(
    val directory: String,
    val tokens: List<String>,
    val environment: Map<String, String> = emptyMap(),
)
