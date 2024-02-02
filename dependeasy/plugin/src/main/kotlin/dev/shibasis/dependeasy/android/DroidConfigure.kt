package dev.shibasis.dependeasy.android

import dev.shibasis.dependeasy.Version
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.getting
import org.gradle.kotlin.dsl.invoke
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget

class AndroidConfiguration(
    var targetModifier: KotlinAndroidTarget.() -> Unit = {},
    var dependencies: KotlinDependencyHandler.() -> Unit = {}
)
fun KotlinMultiplatformExtension.droid(
    parentSourceSet: KotlinSourceSet,
    configuration: AndroidConfiguration.() -> Unit = {}
) {
    val configure = AndroidConfiguration().apply(configuration)
    androidTarget("droid") {
        publishLibraryVariants("release", "debug")
        compilations.all {
            kotlinOptions.jvmTarget = Version.SDK.Java.asString
        }
        configure.targetModifier(this)
    }

    sourceSets {
        val droidMain by getting {
            kotlin.srcDir("droidMain")
            dependsOn(parentSourceSet)
            dependencies {
                configure.dependencies(this)
            }
        }
    }
}