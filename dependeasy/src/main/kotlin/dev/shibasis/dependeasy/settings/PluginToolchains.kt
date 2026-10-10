package dev.shibasis.dependeasy.settings

import org.gradle.api.initialization.Settings
import dev.shibasis.dependeasy.toolchain.ToolchainVersions as Versions

private val pluginVersions = mapOf(
    "org.springframework.boot" to Versions.SpringBoot,
    "com.android.base" to Versions.Agp,
    "com.android.application" to Versions.Agp,
    "com.android.library" to Versions.Agp,
    "org.jetbrains.compose" to Versions.Compose,
    "com.google.devtools.ksp" to Versions.Ksp,
    "com.google.firebase.crashlytics" to Versions.Crashlytics,
    "com.google.gms.google-services" to Versions.GoogleServices,
    "com.codingfeline.buildkonfig" to Versions.BuildKonfig,
    "app.cash.sqldelight" to Versions.SqlDelight,
    "org.jetbrains.kotlinx.benchmark" to Versions.KotlinBenchmark,
)

/** Shared defaults; an explicit plugin version remains the low-level escape hatch. */
internal fun Settings.pluginToolchains() {
    pluginManagement.resolutionStrategy.eachPlugin {
        if (requested.version != null) return@eachPlugin
        val version = pluginVersions[requested.id.id]
            ?: Versions.Kotlin.takeIf { requested.id.id.startsWith("org.jetbrains.kotlin.") }
        version?.let(::useVersion)
    }
}
