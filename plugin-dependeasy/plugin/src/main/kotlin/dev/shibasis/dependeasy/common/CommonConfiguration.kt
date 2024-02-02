package dev.shibasis.dependeasy.common

import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.getting
import org.gradle.kotlin.dsl.invoke
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

class CommonConfiguration(
    var dependencies: KotlinDependencyHandler.() -> Unit = {},
    var testDependencies: KotlinDependencyHandler.() -> Unit = {}
)

fun KotlinMultiplatformExtension.common(
    configuration: CommonConfiguration.() -> Unit = {}
): Pair<KotlinSourceSet, KotlinSourceSet> {
    val configure = CommonConfiguration().apply(configuration)

    lateinit var _commonMain: KotlinSourceSet
    lateinit var _commonTest: KotlinSourceSet

    sourceSets {
        all {
            languageSettings.apply {
                optIn("kotlin.js.ExperimentalJsExport")
            }
        }
        val commonMain by getting {
            kotlin.srcDir("commonMain")
            dependencies {
                configure.dependencies(this)
            }
        }
        _commonMain = commonMain
        val commonTest by getting {
            kotlin.srcDir("commonTest")
            dependencies {
                api(kotlin("test"))
                configure.testDependencies(this)
            }
        }
        _commonTest = commonTest
    }

    return Pair(_commonMain, _commonTest)
}