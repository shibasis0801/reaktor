@file:Suppress("UnstableApiUsage")
package dev.shibasis.dependeasy.android

import com.android.build.api.dsl.ApplicationBuildFeatures
import com.android.build.api.dsl.BuildFeatures
import com.android.build.api.dsl.CompileOptions
import com.android.build.api.dsl.LibraryBuildFeatures
import com.android.build.api.dsl.Packaging
import com.android.build.api.dsl.LibraryExtension
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import dev.shibasis.dependeasy.Versions
import org.gradle.kotlin.dsl.get

fun BuildFeatures.defaults() {
    prefab = true
}

fun ApplicationBuildFeatures.defaults() {
    (this as BuildFeatures).defaults()
}

fun LibraryBuildFeatures.defaults() {
    (this as BuildFeatures).defaults()
}

fun Packaging.includeNativeLibs() {
    // Every native module uses the same pinned NDK and shared C++ runtime.
    jniLibs.pickFirsts.add("**/libc++_shared.so")
}

fun CompileOptions.defaults() {
    sourceCompatibility = Versions.SDK.AndroidJava.asEnum
    targetCompatibility = Versions.SDK.AndroidJava.asEnum
    isCoreLibraryDesugaringEnabled = true
}

fun LibraryExtension.defaults(
    namespace: String,
) {
    this.namespace = namespace
    compileSdk = Versions.SDK.compileSdk
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = Versions.SDK.minSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions { defaults() }
    buildFeatures { defaults() }
    packaging { includeNativeLibs() }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }
}


fun BaseAppModuleExtension.defaults(
    appID: String,
) {
    compileSdk = Versions.SDK.compileSdk
    ndkVersion = Versions.SDK.ndkVersion

    namespace = appID
    defaultConfig {
        applicationId = appID
        minSdk = Versions.SDK.minSdk
        targetSdk = Versions.SDK.targetSdk
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions { defaults() }
    packaging { includeNativeLibs() }
    buildFeatures { defaults() }
}
