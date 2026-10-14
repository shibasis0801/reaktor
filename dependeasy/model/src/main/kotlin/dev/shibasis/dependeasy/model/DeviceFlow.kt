package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

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
    val uninstallOnSignatureMismatch: Boolean = false,
    val processPrefix: List<String> = emptyList(),
    val artifacts: String = "on-failure",
)
