package dev.shibasis.dependeasy

import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

object Version {
    object SDK {
        const val minSdk = 26
        const val compileSdk = 35
        const val targetSdk = 35
        const val ndkVersion = "25.0.8775105"
        const val CMake = "3.22.1"

        const val targetDarwin = "13"

        const val Kotlin = "2.3.0-Beta2"
        const val SpringBoot = "4.0.0"

        object Java {
            val asTarget = JvmTarget.JVM_21
            val asEnum = JavaVersion.VERSION_21
            val asString = "21"
            val asInt = asString.toInt() // wtf is wrong with gradle and java version types
        }
    }

    // Android
    const val Activity = "1.9.3"
    const val Fragment = "1.8.5"
    const val Lifecycle = "2.4.0"
    const val Navigation = "2.3.2"

    // Web
    const val KotlinJSWrappers = "2025.10.4"

    // Data
    const val SQLDelight = "2.0.0"
    const val Exposed = "1.0.0-beta-2"
    const val OkHttp = "4.12.0"
    const val WorkManager = "2.9.0"
    const val Ktor = "3.1.0"
    const val Koin = "4.1.0"
    const val KoinAnnotations = "2.0.0"

    // Cloud
    const val Firebase = "32.0.0"

    // Android Camera
    const val CameraX = "1.3.4"

    // KMM Async
    const val Coroutines = "1.10.1"
    const val Kermit = "2.0.5"
    const val Serialization = "1.9.0"
    const val Seskar = "4.25.0"

    // DevTools
    const val LeakCanary = "2.8.1"
    const val SoLoader = "0.10.1"

    val architectures = listOf(
//        "armeabi-v7a",
        "x86",
        "arm64-v8a",
        "x86_64"
    )
    val nativeLibraries = listOf(
        "libc++_shared.so",
        "libreactnativejni.so",
        "libfbjni.so",
        "libfolly_runtime.so",
        "libglog.so",
        "libjsi.so",
        // todo stupid hack fix
        "libhermes.so",
        "libFlatInvokerCore.so",
        "libFlatInvokerFFI.so",
        "libFlatInvokerReact.so"
    )
}
