package dev.shibasis.dependeasy.versions

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.toolchain.ToolchainVersions

object TelemetryLibraries {
    private const val OpenTelemetryVersion = "0.1.0"
    const val Api = "io.opentelemetry.kotlin:api:$OpenTelemetryVersion"
    const val Implementation = "io.opentelemetry.kotlin:implementation:$OpenTelemetryVersion"
    const val Noop = "io.opentelemetry.kotlin:noop:$OpenTelemetryVersion"
}
