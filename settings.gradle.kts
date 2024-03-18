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
        val kotlinVersion = "1.9.21"
        val agpVersion = "7.4.2"
        val crashlyticsVersion = "2.9.9"

        id("com.google.firebase.crashlytics").version("2.9.9")
        id("com.google.devtools.ksp").version("1.9.21-1.0.16")
        id("org.jetbrains.compose").version("1.5.11")
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



        id("app.cash.sqldelight").version("2.0.0")
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

includeBuild("tester-react/android")