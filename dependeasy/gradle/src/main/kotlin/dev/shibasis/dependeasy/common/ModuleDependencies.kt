package dev.shibasis.dependeasy.common

import dev.shibasis.dependeasy.model.ModuleHandle
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler

class KotlinDependencies internal constructor(private val delegate: KotlinDependencyHandler) : KotlinDependencyHandler by delegate {
    fun implementation(artifact: dev.shibasis.dependeasy.handles.KernelArtifact<*>) {
        delegate.implementation(delegate.project.files(artifact.location))
    }

    fun api(module: ModuleHandle, vararg others: ModuleHandle) {
        (listOf(module) + others).forEach { delegate.api(delegate.project.dependencyNotation(it)) }
    }

    fun implementation(module: ModuleHandle, vararg others: ModuleHandle) {
        (listOf(module) + others).forEach { delegate.implementation(delegate.project.dependencyNotation(it)) }
    }
}

class JvmDependencies internal constructor(private val delegate: DependencyHandler) : DependencyHandler by delegate {
    fun api(module: ModuleHandle, vararg others: ModuleHandle) {
        (listOf(module) + others).forEach { delegate.add("api", it.coordinate) }
    }

    fun implementation(module: ModuleHandle, vararg others: ModuleHandle) {
        (listOf(module) + others).forEach { delegate.add("implementation", it.coordinate) }
    }

    fun testImplementation(module: ModuleHandle, vararg others: ModuleHandle) {
        (listOf(module) + others).forEach { delegate.add("testImplementation", it.coordinate) }
    }
}

internal fun org.gradle.api.Project.dependencyNotation(module: ModuleHandle): Any =
    if (module.build.name == rootProject.name) dependencies.project(mapOf("path" to module.path)) else module.coordinate
