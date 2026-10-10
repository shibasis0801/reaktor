package dev.shibasis.dependeasy.module

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

internal data class JvmExecution(val classpath: FileCollection, val classes: String, val output: FileCollection)

internal fun Project.jvmExecution(compilation: String): JvmExecution {
    val multiplatform = extensions.findByType(KotlinMultiplatformExtension::class.java)
    return if (multiplatform != null) {
        val target = multiplatform.targets.getByName("jvm") as KotlinJvmTarget
        val selected = target.compilations.findByName(compilation) ?: target.compilations.create(compilation) {
            associateWith(target.compilations.getByName("main"))
            defaultSourceSet.kotlin.srcDir("src/$compilation/kotlin")
        }
        JvmExecution(files(selected.output.allOutputs, selected.runtimeDependencyFiles), selected.compileAllTaskName, selected.output.allOutputs)
    } else {
        val sources = extensions.getByType<JavaPluginExtension>().sourceSets
        val selected = sources.findByName(compilation) ?: sources.create(compilation) {
            val main = sources.getByName("main")
            configurations.getByName(implementationConfigurationName)
                .extendsFrom(configurations.getByName(main.implementationConfigurationName))
            configurations.getByName(runtimeOnlyConfigurationName)
                .extendsFrom(configurations.getByName(main.runtimeOnlyConfigurationName))
            compileClasspath += main.output
            runtimeClasspath += main.output
        }
        JvmExecution(selected.runtimeClasspath, selected.classesTaskName, selected.output)
    }
}
