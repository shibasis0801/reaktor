import java.nio.file.*
import dev.shibasis.dependeasy.utils.*

rootProject.name = "flatInvoker"

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
        val sqldelightVersion = extra["sqldelight.version"] as String

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
        id("dev.shibasis.dependeasy.library")
        id("dev.shibasis.dependeasy.application")
    }
}

plugins {
    id("dev.shibasis.dependeasy.settings")
}

/*
I had to change few things in the flatbuffers repo
1. Disable tests in cmakeLists
option(FLATBUFFERS_BUILD_TESTS "Enable the build of tests and samples." OFF)

2. Disable GenerateFBTestClasses task in flatbuffers-kotlin build.gradle.kts

3. added this for new gradle versions (revert please)
 sourceSets {
    all {
      languageSettings.optIn("kotlin.experimental.ExperimentalNativeApi")
    }

The reason was flatc, the binary was not accessible from the path.
If we setup that, these hacks should not be needed.
*/
val githubDir = file(".github_modules")

gitDependency("https://github.com/google/flatbuffers.git", githubDir)
gitDependency("https://github.com/google/googletest.git", githubDir)
gitDependency("https://github.com/JetBrains-Research/reflekt.git", githubDir)

includeWithPath("flatbuffers-kotlin", ".github_modules/flatbuffers/kotlin/flatbuffers-kotlin")
include(":flatinvoker-core")
include(":flatinvoker-ffi")
include(":flatinvoker-react")
include(":flatinvoker-compiler")
include(":reaktor-core")
include(":reaktor-io")
include(":reaktor-ui")
include(":reaktor-auth")
include(":reaktor-media")
include(":reaktor-navigation")

//includeBuild("tester-react/android")