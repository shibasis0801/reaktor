import java.nio.file.*
import dev.shibasis.dependeasy.utils.*

rootProject.name = "flatInvoker"

pluginManagement {
    includeBuild("dependeasy")

    repositories {
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google()
        maven(url = "$rootDir/tester-react/node_modules/react-native/android")
        mavenCentral {
            content {
                excludeGroup("com.facebook.react")
            }
        }
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


gradle.beforeProject {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
            compilerOptions {
                freeCompilerArgs.add("-opt-in=kotlin.experimental.ExperimentalNativeApi")
            }
        }
    }


val githubDir = file(".github_modules")

githubDir.apply {
    gitDependency("https://github.com/google/googletest.git")
    gitDependency("https://github.com/JetBrains-Research/reflekt.git")
    gitDependency("https://github.com/tmikov/hermes-jsi-demos.git")
    gitDependency("https://github.com/facebook/hermes.git")
    gitDependency("https://github.com/leetal/ios-cmake.git")
}



fun linkFlatBuffers(
    githubDirectory: File,
    buildDirectory: File = File(githubDirectory, "flatbuffers"),
    url: String = "https://github.com/shibasis0801/flatbuffers.git"
) {
    githubDirectory.gitDependency(url)
    val flatc = File(buildDirectory, "flatc")
    if (!flatc.exists()) {
        println("Generating CMake build for flatbuffers")
        exec {
            workingDir = buildDirectory
            commandLine("cmake", "-G", "Unix Makefiles")
        }

        println("Building flatc with make...")
        exec {
            workingDir = buildDirectory
            commandLine("make", "-j")
        }
    } else {
        println("flatc already built.")
    }

    if (!flatc.exists()) {
        throw GradleException("Failed to build flatc binary. Check the build logs for details.")
    } else {
        println("flatc binary built successfully at ${flatc.absolutePath}")
    }

    includeBuild(".github_modules/flatbuffers/kotlin/convention-plugins")
    includeWithPath("flatbuffers-kotlin", ".github_modules/flatbuffers/kotlin/flatbuffers-kotlin")
}
linkFlatBuffers(githubDir)


//includeBuild(".github_modules/hermes/android")
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
include(":tester-android")
//includeBuild("tester-react")

