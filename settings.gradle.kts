rootProject.name = "reaktor"

pluginManagement {
    includeBuild("dependeasy")
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}

plugins {
    id("dev.shibasis.dependeasy.settings")
}

//include(":reaktor-react") // will fix later


include(":reaktor-compiler")
include(":reaktor-core")
include(":reaktor-code")
include(":reaktor-flexbuffer")
include(":reaktor-ffi")
include(":reaktor-io")
include(":reaktor-db")
include(":reaktor-ui")
include(":reaktor-tactile")
include(":reaktor-surface")
include(":reaktor-surface-compose")
include(":reaktor-auth")
include(":reaktor-auth-core")
include(":reaktor-media")
include(":reaktor-notification")
include(":reaktor-notification-fcm")
include(":reaktor-notification-service")
include(":reaktor-graph-port")
include(":reaktor-graph-runtime")
include(":reaktor-graph")
include(":reaktor-service")
include(":reaktor-crypto")
include(":reaktor-telemetry")
include(":reaktor-performance")
include(":reaktor-location")
include(":reaktor-sensors")
include(":reaktor-health")
include(":reaktor-work")
include(":compose-flow")
include(":reaktor-blueprint")
include(":reaktor-cloudflare")
include(":reaktor-mcp")
include(":reaktor-web")
include(":reaktor-google")
include(":reaktor-secrets")
include(":reaktor-security")
include(":reaktor-tooling")
include(":reaktor-devtools")
include(":reaktor-idb")
include(":reaktor-conductor")
include(":reaktor-cloud")

include(":reaktor-apple-export")
