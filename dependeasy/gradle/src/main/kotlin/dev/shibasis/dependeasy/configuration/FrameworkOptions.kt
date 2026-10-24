package dev.shibasis.dependeasy.configuration

import dev.shibasis.dependeasy.model.BuildOption
import dev.shibasis.dependeasy.publishing.PublicationDefaults

object FrameworkOptions {
    val Version = BuildOption.string(PublicationDefaults.VersionOptionName, PublicationDefaults.Version)
    val WranglerDirectory = BuildOption.string("wranglerPath")
    val DeviceProbe = BuildOption.boolean("reaktor.web.deviceProbe")
    val ServerHost = BuildOption.string("reaktor.server.host", "0.0.0.0")
    val DesktopClassSharing = BuildOption.boolean("reaktor.desktop.cds", true)
    val ComposeSourceInformation = BuildOption.boolean("reaktor.compose.sourceInformation", true)
    val Crashlytics = BuildOption.boolean("reaktor.crashlytics")
    val PublishCrashlytics = BuildOption.boolean("dependeasy.publishCrashlytics")
    val MavenLocal = BuildOption.boolean("dependeasy.useMavenLocal")
    val GitHubPackagesUsername = BuildOption.string("dependeasy.githubPackagesUsername")
    val GitHubPackagesToken = BuildOption.string("dependeasy.githubPackagesToken", sensitive = true)
}
