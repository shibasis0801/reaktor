package dev.shibasis.dependeasy.common

import dev.shibasis.dependeasy.Version
import org.gradle.kotlin.dsl.invoke
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

open class Configuration<Target> {
    internal var dependencies: KotlinDependencyHandler.() -> Unit = {}
        private set
    internal var sourceSetModifier: KotlinSourceSet.() -> Unit = {}
        private set

    internal var testDependencies: KotlinDependencyHandler.() -> Unit = {}
        private set

    internal var targetModifier: Target.() -> Unit = {}

    fun dependencies(fn: KotlinDependencyHandler.() -> Unit) {
        this.dependencies = fn
    }

    fun sourceSetModifier(fn: KotlinSourceSet.() -> Unit) {
        this.sourceSetModifier = fn
    }

    fun testDependencies(fn: KotlinDependencyHandler.() -> Unit) {
        this.testDependencies = fn
    }

    fun targetModifier(fn: Target.() -> Unit) {
        this.targetModifier = fn
    }
}

class CommonConfiguration: Configuration<Unit>()

@OptIn(ExperimentalKotlinGradlePluginApi::class)
fun KotlinMultiplatformExtension.common(
    configuration: CommonConfiguration.() -> Unit = {}
) {
    jvmToolchain(Version.SDK.Java.asInt)
    val configure = CommonConfiguration().apply(configuration)

    sourceSets {
        compilerOptions {
            freeCompilerArgs.add("-Xexpect-actual-classes")
            freeCompilerArgs.add("-XXLanguage:+JsAllowExportingSuspendFunctions")
        }
        all {
            languageSettings.apply {
                optIn("kotlin.js.ExperimentalJsExport")
            }
        }
        commonMain {
            configure.sourceSetModifier(this)
            dependencies {
                configure.dependencies(this)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(kotlin("test-annotations-common"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${Version.Coroutines}")
            implementation("app.cash.turbine:turbine:1.2.1")
            configure.testDependencies(this)
        }
    }
}