package dev.shibasis.dependeasy.server

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
    parentSourceSet: KotlinSourceSet,
    configuration: ServerConfiguration.() -> Unit
): Pair<KotlinJvmTarget, KotlinSourceSet> {
    val configure = ServerConfiguration().apply(configuration)

    val target = jvm("server") {
        configure.targetModifier(this)
        this.compilations.forEach {
            it.kotlinOptions.jvmTarget = "11"
        }
    }

    lateinit var sourceSet: KotlinSourceSet

    sourceSets {
        val serverMain by getting {
            kotlin.srcDir("serverMain")
            dependsOn(parentSourceSet)
            dependencies {
                configure.dependencies(this)
            }
        }
        sourceSet = serverMain
    }

    return Pair(target, sourceSet)
}
