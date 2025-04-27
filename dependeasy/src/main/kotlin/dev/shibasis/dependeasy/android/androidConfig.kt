@file:Suppress("UnstableApiUsage")
package dev.shibasis.dependeasy.android

import com.android.build.api.dsl.ApplicationBuildFeatures
import com.android.build.api.dsl.BuildFeatures
import com.android.build.api.dsl.CmakeFlags
import com.android.build.api.dsl.CompileOptions
import com.android.build.api.dsl.ExternalNativeBuild
import com.android.build.api.dsl.LibraryBuildFeatures
import com.android.build.api.dsl.Packaging
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.internal.dsl.ExternalNativeCmakeOptions
import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.utils.exclude
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

fun Packaging.includeNativeLibs() {
    Version.nativeLibraries.forEach {
        pickFirst("**/$it")
    }
    zip(Version.architectures, Version.nativeLibraries)
        .forEach { (arch, lib) ->
            jniLibs.pickFirsts.add("**/$arch/$lib")
        }
}

fun Packaging.excludeNativeLibs() {
    Version.nativeLibraries.forEach {
        jniLibs.excludes.add("**/$it")
    }
}

fun CmakeFlags.defaults(name: String) {
    cFlags.addAll(listOf("-Wall", "-Werror", "-fexceptions", "-fPIC", "-frtti", "-DWITH_INSPECTOR=1", "-O2"))
    arguments.addAll(listOf("-DCMAKE_VERBOSE_MAKEFILE=1", "-DANDROID_STL=c++_shared", "-DNAME=$name"))
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
    cmakeLists: File? = null,
    cmakeProjectName: String = "",
) {
    this.namespace = namespace
    compileSdk = Version.SDK.compileSdk
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
// temporary todo remove1
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }


    defaultConfig {
        minSdk = Version.SDK.minSdk
        externalNativeBuild { cmake { defaults(cmakeProjectName) } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (cmakeLists != null) {
        externalNativeBuild {
            defaults(cmakeLists)
        }
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
//            proguardFiles(
//                getDefaultProguardFile("proguard-android-optimize.txt"),
//                "proguard-rules.pro"
//            )
        }
    }
}


fun BaseAppModuleExtension.defaults(
    appID: String,
    cmakeLists: File? = null,
    cmakeProjectName: String = ""
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
            externalNativeBuild { cmake { defaults(cmakeProjectName) } }
        }
    }

    cmakeLists?.apply {
        externalNativeBuild { defaults(cmakeLists) }
    }

    compileOptions { defaults() }
    packaging { includeNativeLibs() }
    buildFeatures { defaults() }
//    composeOptions {
//        kotlinCompilerExtensionVersion = Version.ComposeCompiler
//        useLiveLiterals = false
//    }
}

