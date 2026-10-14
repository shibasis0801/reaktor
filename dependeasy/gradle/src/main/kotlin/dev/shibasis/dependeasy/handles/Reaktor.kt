package dev.shibasis.dependeasy.handles

import dev.shibasis.dependeasy.model.BuildHandle
import dev.shibasis.dependeasy.model.ModuleHandle
import org.gradle.api.GradleException
import org.gradle.api.Project

object Reaktor {
    val build = BuildHandle("reaktor", "dev.shibasis")
    val Compiler = ModuleHandle(build, "reaktor-compiler")
    val Core = ModuleHandle(build, "reaktor-core")
    val Code = ModuleHandle(build, "reaktor-code")
    val Flexbuffer = ModuleHandle(build, "reaktor-flexbuffer")
    val Ffi = ModuleHandle(build, "reaktor-ffi")
    val Io = ModuleHandle(build, "reaktor-io")
    val Db = ModuleHandle(build, "reaktor-db")
    val Ui = ModuleHandle(build, "reaktor-ui")
    val Tactile = ModuleHandle(build, "reaktor-tactile")
    val Surface = ModuleHandle(build, "reaktor-surface")
    val SurfaceCompose = ModuleHandle(build, "reaktor-surface-compose")
    val Auth = ModuleHandle(build, "reaktor-auth")
    val AuthCore = ModuleHandle(build, "reaktor-auth-core")
    val Media = ModuleHandle(build, "reaktor-media")
    val Notification = ModuleHandle(build, "reaktor-notification")
    val NotificationFcm = ModuleHandle(build, "reaktor-notification-fcm")
    val NotificationService = ModuleHandle(build, "reaktor-notification-service")
    val NotificationFramework = dev.shibasis.dependeasy.darwin.AppleFrameworkSpec(NotificationService, "ReaktorNotifications")
    val GraphPort = ModuleHandle(build, "reaktor-graph-port")
    val GraphRuntime = ModuleHandle(build, "reaktor-graph-runtime")
    val Graph = ModuleHandle(build, "reaktor-graph")
    val Service = ModuleHandle(build, "reaktor-service")
    val Crypto = ModuleHandle(build, "reaktor-crypto")
    val Telemetry = ModuleHandle(build, "reaktor-telemetry")
    val Performance = ModuleHandle(build, "reaktor-performance")
    val Location = ModuleHandle(build, "reaktor-location")
    val Sensors = ModuleHandle(build, "reaktor-sensors")
    val Health = ModuleHandle(build, "reaktor-health")
    val Work = ModuleHandle(build, "reaktor-work")
    val ComposeFlow = ModuleHandle(build, "compose-flow")
    val Blueprint = ModuleHandle(build, "reaktor-blueprint")
    val Cloudflare = ModuleHandle(build, "reaktor-cloudflare")
    val Mcp = ModuleHandle(build, "reaktor-mcp")
    val Web = ModuleHandle(build, "reaktor-web")
    val Google = ModuleHandle(build, "reaktor-google")
    val Secrets = ModuleHandle(build, "reaktor-secrets")
    val Security = ModuleHandle(build, "reaktor-security")
    val Tooling = ModuleHandle(build, "reaktor-tooling")
    val Devtools = ModuleHandle(build, "reaktor-devtools")
    val Idb = ModuleHandle(build, "reaktor-idb")
    val Conductor = ModuleHandle(build, "reaktor-conductor")
    val Cloud = ModuleHandle(build, "reaktor-cloud")
    val AppleExport = ModuleHandle(build, "reaktor-apple-export")
    val modules = listOf(Compiler, Core, Code, Flexbuffer, Ffi, Io, Db, Ui, Tactile, Surface, SurfaceCompose,
        Auth, AuthCore, Media, Notification, NotificationFcm, NotificationService, GraphPort, GraphRuntime, Graph,
        Service, Crypto, Telemetry, Performance, Location, Sensors, Health, Work, ComposeFlow, Blueprint,
        Cloudflare, Mcp, Web, Google, Secrets, Security, Tooling, Devtools, Idb, Conductor, Cloud, AppleExport)

    internal fun check(project: Project) {
        if (project.rootProject === project && project.name == build.name) {
            val declared = modules.map { it.path }.toSet()
            val included = project.subprojects.map { it.path }.toSet()
            if (declared != included) throw GradleException("Reaktor catalog differs from settings includes: missing=${declared - included}, uncatalogued=${included - declared}")
        }
    }
}

object Dependeasy { val build = BuildHandle("dependeasy", "dev.shibasis.dependeasy") }
