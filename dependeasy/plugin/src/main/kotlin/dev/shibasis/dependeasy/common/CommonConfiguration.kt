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
    var testDependencies: KotlinDependencyHandler.() -> Unit = {}
)

@OptIn(ExperimentalKotlinGradlePluginApi::class)
fun KotlinMultiplatformExtension.common(
    configuration: CommonConfiguration.() -> Unit = {}
): Pair<KotlinSourceSet, KotlinSourceSet> {
    jvmToolchain(Version.SDK.Java.asInt)
    val configure = CommonConfiguration().apply(configuration)

    lateinit var _commonMain: KotlinSourceSet
    lateinit var _commonTest: KotlinSourceSet

    sourceSets {
        compilerOptions {
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
        all {
            languageSettings.apply {
                optIn("kotlin.js.ExperimentalJsExport")
            }
        }
        val commonMain by getting {
            dependencies {
                configure.dependencies(this)
            }
        }
        _commonMain = commonMain
        val commonTest by getting {
            dependencies {
                api(kotlin("test"))
                api(kotlin("test-annotations-common"))
                configure.testDependencies(this)
            }
        }
        _commonTest = commonTest
    }

    return Pair(_commonMain, _commonTest)
}