package dev.shibasis.dependeasy

import org.gradle.api.JavaVersion

object Version {
    object SDK {
        const val minSdk = 24
        const val compileSdk = 34
        const val targetSdk = 34
        const val ndkVersion = "25.0.8775105"
        const val CMake = "3.22.1"

        const val targetDarwin = "12"

        const val Kotlin = "2.0.0"
        const val Vertx = "4.4.2"

        object Java {
            val asEnum = JavaVersion.VERSION_11
            val asString = "11"
            val asInt = asString.toInt() // wtf is wrong with gradle and java version types
        }
    }

    // Android
//    const val Compose = "1.2.0"
    const val Activity = "1.6.1"
    const val Fragment = "1.3.6"
    const val Lifecycle = "2.4.0"
    const val Navigation = "2.3.2"
    const val ComposeCompiler = "1.5.6"

    // Web
    const val KotlinJSWrappers = "1.0.0-pre.758"

    // Data
    const val SQLDelight = "2.0.0"
    const val OkHttp = "4.10.0"
    const val WorkManager = "2.9.0"
    const val Ktor = "2.3.0"

    // Image
    const val Coil = "3.0.0-SNAPSHOT"

    // Cloud
    const val Firebase = "32.0.0"

    // Android Camera
    const val CameraX = "1.2.3"


    // Database

    // KMM Async
    const val Coroutines = "1.8.0-RC"
    const val Kermit = "2.0.3"
    const val Serialization = "1.7.1"

    // DevTools
    const val Flipper = "0.162.0"
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
        "libFlatInvokerCore.so",
        "libFlatInvokerFFI.so",
        "libFlatInvokerReact.so"
    )

}
