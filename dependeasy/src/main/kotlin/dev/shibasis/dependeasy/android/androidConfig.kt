@file:Suppress("UnstableApiUsage")
package dev.shibasis.dependeasy.android

import com.android.build.api.dsl.ApplicationBuildFeatures
import com.android.build.api.dsl.BuildFeatures
import com.android.build.api.dsl.CompileOptions
import com.android.build.api.dsl.LibraryBuildFeatures
import com.android.build.api.dsl.Packaging
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.utils.exclude
import org.gradle.api.artifacts.Configuration
import org.gradle.kotlin.dsl.NamedDomainObjectContainerScope
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

fun CompileOptions.defaults() {
    sourceCompatibility = Version.SDK.AndroidJava.asEnum
    targetCompatibility = Version.SDK.AndroidJava.asEnum
    isCoreLibraryDesugaringEnabled = true
}

fun NamedDomainObjectContainerScope<Configuration>.defaults() {
    all {
        exclude(module = "fbjni-java-only")
    }
}

fun LibraryExtension.defaults(
    namespace: String,
) {
    this.namespace = namespace
    compileSdk = Version.SDK.compileSdk
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }


    defaultConfig {
        minSdk = Version.SDK.minSdk
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
    }

    compileOptions { defaults() }
    packaging { includeNativeLibs() }
    buildFeatures { defaults() }
}
