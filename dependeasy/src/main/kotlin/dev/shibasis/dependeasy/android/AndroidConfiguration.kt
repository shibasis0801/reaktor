package dev.shibasis.dependeasy.android

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.Configuration
import dev.shibasis.dependeasy.native.nativeConfigurationOrNull
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.exclude
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget

import dev.shibasis.dependeasy.native.configureAndroidNative

class AndroidConfiguration(

): Configuration<KotlinAndroidTarget>() {
    internal var integrationTestDependencies: KotlinDependencyHandler.() -> Unit = {}
        private set

    fun integrationTestDependencies(fn: KotlinDependencyHandler.() -> Unit = {}) {
        this.integrationTestDependencies = fn
    }
}

fun KotlinMultiplatformExtension.droid(
    configuration: AndroidConfiguration.() -> Unit = {}
) {
    val configure = AndroidConfiguration().apply(configuration)
    val native = project.nativeConfigurationOrNull
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
        publishLibraryVariants("release", "debug")

        compilerOptions {
            jvmTarget.set(Versions.SDK.AndroidJava.asTarget)
            freeCompilerArgs.add("-Xstring-concat=inline")
        }

        configure.targetModifier(this)
    }

    project.configureAndroidNative()

    sourceSets {
        androidMain {
            configure.sourceSetModifier(this)
            dependencies {
                configure.dependencies(this)
                native?.android?.resolvedPrefabs.orEmpty().forEach { prefab ->
                    implementation(prefab.runtimeDependency) {
                        prefab.runtimeExcludes.forEach { (group, module) -> exclude(group = group, module = module) }
                    }
                }
            }
        }

        findByName("androidUnitTest")?.dependencies {
            implementation(kotlin("test"))
            configure.testDependencies(this)
        }
        getByName("androidInstrumentedTest") {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(Versions.Android.TestCoreKtx)
                implementation(Versions.Tooling.JUnit4)
                implementation(Versions.Android.TestJUnitLegacy)
                implementation(Versions.Android.TestJUnitKtx)
                implementation(Versions.Android.Espresso)
                configure.integrationTestDependencies(this)
            }
        }
    }
}
