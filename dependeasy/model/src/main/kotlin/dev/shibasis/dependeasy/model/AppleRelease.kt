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
    val distribution: ReleaseDistribution? = null,
)

@Serializable data class ReleaseCommand(
    val directory: String,
    val tokens: List<String>,
    val environment: Map<String, String> = emptyMap(),
)

@Serializable data class ReleaseDistribution(
    val appleId: String,
    val appStoreTeamId: String,
    val firebaseGroup: String,
    val testflightExternalGroups: List<String>,
    val playInternalTrack: String = "internal",
    val playInternalStatus: String = "completed",
    val playProductionTrack: String = "production",
    val playProductionStatus: String = "draft",
    val firebaseExportMethod: String = "release-testing",
    val appStoreExportMethod: String = "app-store-connect",
    val signingCertificate: String = "Apple Distribution",
    val artifacts: Map<String, String> = emptyMap(),
    val profiles: List<ReleaseProfile> = emptyList(),
) {
    init {
        require(listOf(appleId, appStoreTeamId, firebaseGroup).all(String::isNotBlank))
        require(testflightExternalGroups.isNotEmpty() && testflightExternalGroups.all(String::isNotBlank))
        require(profiles.map { it.bundleId to it.method }.distinct().size == profiles.size) { "Duplicate release profile" }
    }
}

@Serializable data class ReleaseProfile(val bundleId: String, val method: String, val name: String, val fileName: String) {
    init {
        require(bundleId.isNotBlank() && name.isNotBlank())
        require(method in setOf("ad-hoc", "app-store", "development"))
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName && fileName != "." && fileName != "..")
    }
}
