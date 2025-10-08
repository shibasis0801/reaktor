package dev.shibasis.dependeasy.common

import dev.shibasis.dependeasy.Version
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.getting
import org.gradle.kotlin.dsl.invoke
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

class CommonConfiguration(
    var dependencies: KotlinDependencyHandler.() -> Unit = {},
    var testDependencies: KotlinDependencyHandler.() -> Unit = {},
    var sourceSetModifier: KotlinSourceSet.() -> Unit = {}
)

@OptIn(ExperimentalKotlinGradlePluginApi::class)
fun KotlinMultiplatformExtension.common(
    configuration: CommonConfiguration.() -> Unit = {}
) {
    jvmToolchain(Version.SDK.Java.asInt)
    val configure = CommonConfiguration().apply(configuration)

    sourceSets {
        compilerOptions {
            freeCompilerArgs.add("-Xexpect-actual-classes")
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
            api(kotlin("test"))
            api(kotlin("test-annotations-common"))
            api("org.jetbrains.kotlinx:kotlinx-coroutines-test:${Version.Coroutines}")
            api("app.cash.turbine:turbine:1.2.1")
            configure.testDependencies(this)
        }
    }
}