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


val githubDir = file(".github_modules")
githubDir.mkdir()
githubDir.apply {
    gitDependency("https://github.com/tmikov/hermes-jsi-demos.git")
}



fun linkFlatBuffers(
    githubDirectory: File,
    buildDirectory: File = File(githubDirectory, "flatbuffers"),
    url: String = "https://github.com/shibasis0801/flatbuffers.git"
) {
    githubDirectory.gitDependency(url)
    val isWindows = System.getProperty("os.name").contains("Windows")
    val flatc = if(isWindows) File(buildDirectory, "Debug/flatc.exe") else File(buildDirectory, "flatc")
    if (!flatc.exists()) {
        println("Generating CMake build for flatbuffers")
        if (isWindows) {
            println("Generating CMake build for flatbuffers on Windows")
            exec {
                workingDir = buildDirectory
                commandLine("cmake", "-G", "Visual Studio 17 2022")
            }
            println("Building flatc with make...")
            exec {
                workingDir = buildDirectory
                commandLine("cmake", "--build", '.')
            }
        } else {
            println("Generating CMake build for flatbuffers on Unix")
            exec {
                workingDir = buildDirectory
                commandLine("cmake", "-G", "Unix Makefiles")
            }
            println("Building flatc with make...")
            exec {
                workingDir = buildDirectory
                commandLine("make", "-j")
            }
        }


    } else {
        println("flatc already built.")
    }

    if (!flatc.exists()) {
        throw GradleException("Failed to build flatc binary. Check the build logs for details.")
    } else {
        println("flatc binary built successfully at ${flatc.absolutePath}")
    }

    includeWithPath("flatbuffers-kotlin", ".github_modules/flatbuffers/kotlin/flatbuffers-kotlin")
}
linkFlatBuffers(githubDir)

fun linkHermes(
    githubDirectory: File,
    buildDirectory: File = File(githubDirectory, "hermes/debug"),
    url: String = "https://github.com/facebook/hermes.git"
) {
    githubDirectory.gitDependency(url)
    val isWindows = System.getProperty("os.name").contains("Windows")
    val hermesSrcDir = File(githubDirectory, "hermes")
    val hermesBuildDir = buildDirectory
    buildDirectory.mkdir()

    val hermesExecutable = if(isWindows) File(buildDirectory, "bin/Debug/hermes.exe") else File(buildDirectory, "bin/hermes")

    // Check for the existence of jsi.h
    val jsiHeader = File(hermesSrcDir, "API/jsi/jsi/jsi.h")
    if (!jsiHeader.exists()) {
        throw GradleException("${jsiHeader.absolutePath} does not exist.")
    }

    // Check if Hermes executable exists
    if (!hermesExecutable.exists()) {
        if (isWindows) {
            println("Generating CMake build for Hermes on Windows")
            exec {
                workingDir = hermesBuildDir
                commandLine(
                    "cmake",
                    "-G", "Visual Studio 17 2022",
                    "-A", "x64",
                    "-DCMAKE_BUILD_TYPE=Debug",
                    hermesSrcDir.absolutePath
                )
            }
            println("Building Hermes with CMake...")
            exec {
                workingDir = hermesBuildDir
                commandLine("cmake", "--build", ".")
            }
        } else {
            println("Generating CMake build for Hermes on Unix")
            exec {
                workingDir = hermesBuildDir
                commandLine(
                    "cmake",
                    "-G", "Ninja",
                    "-DHERMES_BUILD_APPLE_FRAMEWORK=ON",
                    "-DCMAKE_BUILD_TYPE=Debug",
                    hermesSrcDir.absolutePath
                )
            }
            println("Building Hermes with Ninja...")
            exec {
                workingDir = hermesBuildDir
                commandLine("ninja")
            }
        }

        if (!hermesExecutable.exists()) {
            throw GradleException("Hermes executable still not found after build attempt. Please investigate further.")
        }
    } else {
        println("Hermes executable already built.")
    }

    println("Hermes executable built successfully at ${hermesExecutable.absolutePath}")
}
linkHermes(githubDir)

include(":flatinvoker-core")
include(":flatinvoker-ffi")
//include(":flatinvoker-react") // will fix later
include(":flatinvoker-compiler")
include(":reaktor-core")
include(":reaktor-io")
include(":reaktor-db")
include(":reaktor-ui")
include(":reaktor-auth")
include(":reaktor-media")
include(":reaktor-notification")
include(":reaktor-navigation")
include(":reaktor-location")
include(":reaktor-work")
