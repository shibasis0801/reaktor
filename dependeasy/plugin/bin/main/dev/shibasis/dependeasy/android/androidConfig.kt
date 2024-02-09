@file:Suppress("UnstableApiUsage")
package dev.shibasis.dependeasy.android

import com.android.build.api.dsl.ApplicationBuildFeatures
import com.android.build.api.dsl.BuildFeatures
import com.android.build.api.dsl.CompileOptions
import com.android.build.api.dsl.ExternalNativeBuild
import com.android.build.api.dsl.ExternalNativeCmakeOptions
import com.android.build.api.dsl.LibraryBuildFeatures
import com.android.build.api.dsl.LibraryDefaultConfig
import com.android.build.api.dsl.PackagingOptions
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.utils.exclude
import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.Configuration
import org.gradle.kotlin.dsl.NamedDomainObjectContainerScope
import org.gradle.kotlin.dsl.get
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import java.io.File

fun BuildFeatures.defaults() {
    prefab = true
//    compose = true
}

fun ApplicationBuildFeatures.defaults() {
    (this as BuildFeatures).defaults()
}

fun LibraryBuildFeatures.defaults() {
    (this as BuildFeatures).defaults()
}

fun KotlinJvmOptions.defaults() {
    jvmTarget = Version.SDK.Java.asString
}

fun<First, Second> zip(first: Iterable<First>, second: Iterable<Second>) =
    first.zip(second)

fun PackagingOptions.includeNativeLibs() {
    pickFirst("**/libc++_shared.so") // dangerous
    pickFirst("**/libfolly_runtime.so")
    pickFirst("**/libglog.so")
    pickFirst("**/libfbjni.so")
    zip(Version.architectures, Version.nativeLibraries)
        .forEach { (arch, lib) ->
            jniLibs.pickFirsts.add("**/$arch/$lib")
        }
}

fun PackagingOptions.excludeNativeLibs() {
    Version.nativeLibraries.forEach {
        jniLibs.excludes.add("**/$it")
    }
}


fun LibraryDefaultConfig.defaults() {
    minSdk = Version.SDK.minSdk
    externalNativeBuild { cmake { defaults() } }
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}

fun ExternalNativeCmakeOptions.defaults() {
    cFlags.addAll(listOf("-Wall", "-Werror", "-fexceptions", "-fPIC", "-frtti", "-DWITH_INSPECTOR=1")) // add -O2 for prod
    arguments.addAll(listOf("-DCMAKE_VERBOSE_MAKEFILE=1", "-DANDROID_STL=c++_shared"))
    cppFlags.add("-std=c++20")
}

fun CompileOptions.defaults() {
    sourceCompatibility = Version.SDK.Java.asEnum
    targetCompatibility = Version.SDK.Java.asEnum
}

fun ExternalNativeBuild.defaults(cmakeLists: File) {
    cmake {
        path = cmakeLists
        // pin cmake version to support M1 machines
        version = Version.SDK.CMake
    }
}

fun NamedDomainObjectContainerScope<Configuration>.defaults() {
    all {
        exclude(module = "fbjni-java-only")
    }
}

fun LibraryExtension.defaults(
    namespace: String,
    cmakeLists: File? = null
) {
    this.namespace = namespace
    compileSdk = Version.SDK.compileSdk
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {defaults() }
    if (cmakeLists != null) {
        externalNativeBuild {
            defaults(cmakeLists)
        }
    }

    compileOptions { defaults() }
    buildFeatures { defaults() }
    packagingOptions { includeNativeLibs() }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}


fun BaseAppModuleExtension.defaults(
    appID: String,
    cmakeLists: File? = null,
    enableCompose: Boolean = false,
) {
    compileSdk = Version.SDK.compileSdk
    ndkVersion = Version.SDK.ndkVersion

    namespace = appID
    defaultConfig {
        applicationId = appID
        minSdk = Version.SDK.minSdk
        targetSdk = Version.SDK.targetSdk
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        cmakeLists?.let {
            externalNativeBuild { cmake { defaults() } }
        }
    }

    cmakeLists?.apply {
        externalNativeBuild { defaults(cmakeLists) }
    }

    compileOptions { defaults() }
    packagingOptions { includeNativeLibs() }
    buildFeatures { defaults() }
//    composeOptions {
//        kotlinCompilerExtensionVersion = Version.ComposeCompiler
//        useLiveLiterals = false
//    }
}

