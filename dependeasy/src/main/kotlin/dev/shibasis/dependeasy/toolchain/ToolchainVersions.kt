package dev.shibasis.dependeasy.toolchain

/** Kotlin is the version authority; bootstrap adapters read literal constants before the plugin exists. */
object ToolchainVersions {
    const val JavaBuildImage = "eclipse-temurin:25-jdk@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc"
    const val Java = 25
    const val AndroidJava = 21
    const val SpringBoot = "4.0.0"
    const val BuildpackBuilder = "paketobuildpacks/builder-jammy-tiny@sha256:7600077e3393bf218197414f2f8e71be64ad39c21d4a74cc9335c99f0073ae35"
    const val BuildpackRunImage = "paketobuildpacks/run-jammy-tiny@sha256:bf4c013dbc9e6c0e064b79f750af5494df55d0d74c9f9fdd76d59167825458b9"

    const val Kotlin = "2.4.21"
    // IntelliJ IDEA 2026.2's Android plugin supports the 9.1 series.
    const val Agp = "9.1.1"
    const val Compose = "1.12.1"
    const val ComposeMaterial3 = "1.9.0"
    const val Ksp = "2.3.4"
    const val Crashlytics = "3.0.3"
    const val GoogleServices = "4.4.1"
    const val BuildKonfig = "0.15.1"
    const val SqlDelight = "2.1.0"
    const val SnakeYaml = "2.7"
    const val KotlinBenchmark = "0.4.10"
    const val Jmh = "1.37"
    const val Gradle = "9.7.0"
    const val GradleSha256 = "84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae"

    const val AndroidCompileSdk = 37
    const val AndroidSdkPlatform = "37.0"
    const val AndroidBuildTools = "37.0.0"
    const val AndroidCommandLineTools = "23.0"
    const val AndroidCommandLineToolsArchive = "commandlinetools-linux-16111833_latest.zip"
    const val AndroidCommandLineToolsSha256 = "0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8"
    const val Ndk = "30.0.16248370"
    const val Cmake = "4.0.3"
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
}
