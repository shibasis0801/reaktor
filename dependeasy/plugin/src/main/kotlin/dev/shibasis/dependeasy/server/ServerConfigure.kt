package dev.shibasis.dependeasy.server

import dev.shibasis.dependeasy.Version
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.getting
import org.gradle.kotlin.dsl.invoke
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

class ServerConfiguration(
    var dependencies: KotlinDependencyHandler.() -> Unit = {},
    var targetModifier: KotlinJvmTarget.() -> Unit = {}
)

fun KotlinMultiplatformExtension.server(
    configuration: ServerConfiguration.() -> Unit
) {
    val configure = ServerConfiguration().apply(configuration)

    jvm {
        configure.targetModifier(this)
        this.compilations.forEach {
            it.kotlinOptions.jvmTarget = "11"
        }
    }

    sourceSets {
        jvmMain.dependencies {
            configure.dependencies(this)
        }
    }
}
