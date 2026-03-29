rootProject.name = "reaktor"

pluginManagement {
    includeBuild("dependeasy")
    repositories {
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
        maven("https://maven.pkg.jetbrains.space/kotlin/p/wasm/experimental")
    }

    plugins {
        val crashlyticsVersion = "2.9.9"
        val agpVersion = extra["agp.version"] as String
        val composeVersion = extra["compose.version"] as String
        val kotlinVersion = extra["kotlin.version"] as String
        val kspVersion = extra["ksp.version"] as String
        val sqldelightVersion = extra["sqldelightVersion"] as String

        id("org.jetbrains.compose").version(composeVersion)
        id("org.jetbrains.kotlin.plugin.compose").version(kotlinVersion)

        id("com.google.firebase.crashlytics").version(crashlyticsVersion)
        id("com.google.devtools.ksp").version(kspVersion)
        id("com.google.gms.google-services").version("4.4.0")
        id("com.codingfeline.buildkonfig").version("0.15.1")

        kotlin("jvm").version(kotlinVersion)
        kotlin("multiplatform").version(kotlinVersion)
        kotlin("plugin.serialization").version(kotlinVersion)
        kotlin("android").version(kotlinVersion)
        id("com.android.base").version(agpVersion)
        id("com.android.application").version(agpVersion)
        id("com.android.library").version(agpVersion)
        id("org.jetbrains.kotlinx.benchmark") version "0.4.10"

        id("app.cash.sqldelight").version(sqldelightVersion)
        id("dev.shibasis.dependeasy.application")
    }
}

plugins {
    id("dev.shibasis.dependeasy.settings")
}

gradle.beforeProject {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
            compilerOptions {
                freeCompilerArgs.add("-opt-in=kotlin.experimental.ExperimentalNativeApi")
            }
        }
    }

//include(":reaktor-react") // will fix later


include(":reaktor-compiler")
include(":reaktor-core")
include(":reaktor-flexbuffer")
include(":reaktor-ffi")
include(":reaktor-io")
include(":reaktor-db")
include(":reaktor-ui")
include(":reaktor-tactile")
include(":reaktor-auth")
include(":reaktor-media")
include(":reaktor-notification")
include(":reaktor-graph-port")
include(":reaktor-graph")
include(":reaktor-telemetry")
include(":reaktor-location")
include(":reaktor-work")
include(":reaktor-cloudflare")
include(":reaktor-mcp")
include(":reaktor-web")
include(":reaktor-google")
include(":experiments-cloudflare-hello-worker")
project(":experiments-cloudflare-hello-worker").projectDir = file("experiments/cloudflare-hello-worker")
