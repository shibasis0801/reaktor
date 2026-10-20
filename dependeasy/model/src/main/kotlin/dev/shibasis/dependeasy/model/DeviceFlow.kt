package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable enum class DevicePlatform(val value: String) {
    @SerialName("android") Android("android"),
    @SerialName("ios") Ios("ios"),
}

@Serializable enum class ArtifactPolicy(val value: String) {
    @SerialName("on-failure") OnFailure("on-failure"),
    @SerialName("always") Always("always"),
    @SerialName("never") Never("never"),
}

data class DeviceOptions(val platform: DevicePlatform, val flow: String? = null, val artifacts: ArtifactPolicy = ArtifactPolicy.OnFailure)

@Serializable data class DeviceFlowContract(
    val schema: Int = 1,
    val platform: String,
    val applicationId: String,
    val appOutput: String,
    val appFile: String?,
    val flows: List<String>,
    val runner: String,
    val reportDirectory: String,
    val team: String? = null,
    val wdaBundleId: String? = null,
    val serial: String? = null,
    val environmentKeys: List<String> = emptyList(),
    val environmentAllowList: List<String> = emptyList(),
    val uninstallOnSignatureMismatch: Boolean = false,
    val processPrefix: List<String> = emptyList(),
    val artifacts: String = "on-failure",
)

sealed interface DeviceHook {
    data class Node(val script: String) : DeviceHook
    data class PythonDevice(val script: String) : DeviceHook
}

data class DeviceHarness(val script: String)
