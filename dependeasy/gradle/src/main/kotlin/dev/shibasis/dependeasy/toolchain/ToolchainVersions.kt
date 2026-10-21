package dev.shibasis.dependeasy.toolchain

object ToolchainVersions {
    const val JavaBuildImage = "eclipse-temurin:25-jdk@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
    const val Java = GradlePlugins.CompilerJava
    const val AndroidJava = 21
    const val SpringBoot = GradlePlugins.SpringBoot
    const val BuildpackBuilder = "paketobuildpacks/builder-jammy-tiny@sha256:7600077e3393bf218197414f2f8e71be64ad39c21d4a74cc9335c99f0073ae35"
    const val BuildpackRunImage = "paketobuildpacks/run-jammy-tiny@sha256:bf4c013dbc9e6c0e064b79f750af5494df55d0d74c9f9fdd76d59167825458b9"

    const val Kotlin = GradlePlugins.Kotlin
    // IntelliJ IDEA 2026.2's Android plugin supports the 9.1 series.
    const val Agp = GradlePlugins.Agp
    const val Compose = GradlePlugins.Compose
    const val ComposeMaterial3 = "1.9.0"
    const val Ksp = GradlePlugins.Ksp
    const val Crashlytics = GradlePlugins.Crashlytics
    const val GoogleServices = GradlePlugins.GoogleServices
    const val SqlDelight = GradlePlugins.SqlDelight
    const val SnakeYaml = BuildLibraries.SnakeYaml
    const val KotlinBenchmark = GradlePlugins.KotlinBenchmark
    val HostPaths = mapOf(
        "OPENSSL_ROOT_DIR" to listOf("/opt/homebrew/opt/openssl", "/usr/local/opt/openssl"),
        "ASPROF_LIB" to listOf("/opt/homebrew/lib/libasyncProfiler.dylib", "/usr/local/lib/libasyncProfiler.dylib", "~/.local/lib/libasyncProfiler.dylib"),
        "ASPROF_JAR" to listOf("/opt/homebrew/opt/async-profiler/libexec/async-profiler.jar", "/usr/local/opt/async-profiler/libexec/async-profiler.jar"),
    )
    const val AsyncProfiler = "4.3"
    const val Jmh = "1.37"
    const val Gradle = "9.7.0"
    const val GradleSha256 = "84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae"

    const val AndroidCompileSdk = 37
    const val AndroidSdkPlatform = "37.0"
    const val AndroidBuildTools = "37.0.0"
    const val AndroidCli = "1.0.16625370"
    const val AndroidCliSha256 = "0e56633db6aa75c6c6e8be89a1e4cb6d985258173d3f9d0c0ce5ee9a9d3b8165"
    const val Ndk = "30.0.16248370"
    const val Cmake = "4.0.3"
    const val Ninja = "1.13.1"
    const val CppStandard = "23"
    const val HermesAndroid = "0.82.1"
    const val HermesRevision = "265ef62ff3eb7289d17e366664ac0da82303e101"

    const val SwiftLanguage = "6"
    const val Swift = "6.3"
    const val SwiftRecommended = "6.4"

    const val TypeScript = "7.0.2"
    const val TypeScriptApi = "6.0.3"
    const val Karakum = "1.0.0-alpha.112"
    const val Node = "24.21.0"
    const val NodeMinimum = "22.12.0"
    const val Pnpm = "12.10.1"
    const val Dagger = "0.21.8"
    const val Pulumi = "3.268.0"
}
