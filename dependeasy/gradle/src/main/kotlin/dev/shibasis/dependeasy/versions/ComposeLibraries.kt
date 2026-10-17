package dev.shibasis.dependeasy.versions

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.toolchain.ToolchainVersions

object ComposeLibraries {
    const val Runtime = "org.jetbrains.compose.runtime:runtime:${ToolchainVersions.Compose}"
    const val Foundation = "org.jetbrains.compose.foundation:foundation:${ToolchainVersions.Compose}"
    const val Material3 = "org.jetbrains.compose.material3:material3:${ToolchainVersions.ComposeMaterial3}"
    const val Ui = "org.jetbrains.compose.ui:ui:${ToolchainVersions.Compose}"
    const val UiTest = "org.jetbrains.compose.ui:ui-test:${ToolchainVersions.Compose}"
    const val Resources = "org.jetbrains.compose.components:components-resources:${ToolchainVersions.Compose}"
    val Desktop get() = org.jetbrains.compose.ComposePlugin.DesktopDependencies.currentOs
    const val BackHandler = "org.jetbrains.compose.ui:ui-backhandler:${ToolchainVersions.Compose}"
    const val Coil = "io.coil-kt.coil3:coil-compose:${Versions.Coil}"
    const val CoilKtor = "io.coil-kt.coil3:coil-network-ktor3:${Versions.Coil}"
    const val CoilSvg = "io.coil-kt.coil3:coil-svg:${Versions.Coil}"
    const val DesktopRuntime = "androidx.compose.runtime:runtime-desktop:${ToolchainVersions.Compose}"
    const val DesktopSaveable = "androidx.compose.runtime:runtime-saveable-desktop:${ToolchainVersions.Compose}"
    const val Elk = "org.eclipse.elk:org.eclipse.elk.alg.layered:${Versions.Elk}"
    const val MaterialIcons = "org.jetbrains.compose.material:material-icons-core:1.7.3"
    const val NavigationEvent = "org.jetbrains.androidx.navigationevent:navigationevent-compose:1.1.0"
    const val Xbase = "org.eclipse.xtext:org.eclipse.xtext.xbase.lib:${Versions.XbaseLib}"
}
