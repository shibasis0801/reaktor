package dev.shibasis.dependeasy.model

data class AppleFrameworkSpec(val module: ModuleHandle, val name: String)

enum class Contract { AppleHost, AppleRelease, WorkerFleet, WorkerConfigurations }

data class AppleAppSpec(
    val entryPoint: String,
    val bundleId: String,
    val team: String,
    val settingsPrefix: String,
    val backgroundTasks: List<String> = emptyList(),
    val extension: AppleFrameworkSpec? = null,
    val extensionSettingsPrefix: String? = null,
    val extensionTarget: String = "NotificationService",
    val target: String = "iosApp",
    val version: BuildOption<String>? = null,
    val number: BuildOption<Int>? = null,
    val developmentTools: BuildOption<Boolean>? = null,
    val web: WorkerRef? = null,
    val distribution: ReleaseDistribution? = null,
)
