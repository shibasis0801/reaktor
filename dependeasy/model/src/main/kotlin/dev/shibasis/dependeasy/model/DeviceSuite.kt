package dev.shibasis.dependeasy.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class DeviceFlowSelection(
    val directory: String,
    val files: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
)

data class DeviceSuite(
    val flows: Map<DevicePlatform, DeviceFlowSelection>,
    val environment: Map<String, String> = emptyMap(),
    val options: Map<String, BuildOption<String>> = emptyMap(),
    val environmentAllowList: Set<String> = emptySet(),
    val environmentFiles: List<String> = emptyList(),
    val optionalEnvironmentFiles: List<String> = emptyList(),
    val hook: DeviceHook? = null,
    val harness: DeviceHarness? = null,
    val hookFiles: List<String> = emptyList(),
)

data class DeviceExchange(
    val suite: DeviceSuite,
    val receive: String,
    val send: String,
    val token: String,
    val sendAfter: Duration = 45.seconds,
)
