package dev.shibasis.reaktor.telemetry

import dev.shibasis.reaktor.service.ServiceSpan

data class TelemetryIdentity(val application: String, val installation: String, val platform: String) {
    init {
        require(application.matches(Regex("[a-z][a-z0-9.-]{0,63}")))
        require(installation.matches(Regex("[a-zA-Z0-9_-]{1,96}")))
        require(platform in setOf("android", "ios", "web", "jvm", "unknown"))
    }

    fun attribute(span: ServiceSpan): ServiceSpan = span.copy(
        application = application, installation = installation, platform = platform,
    )
}
