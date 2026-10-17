package dev.shibasis.dependeasy.utils

import org.gradle.kotlin.dsl.DependencyHandlerScope
import org.gradle.kotlin.dsl.exclude
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler

data class exclude(val group: String? = null, val module: String? = null)
data class module(val dependency: String, val excludes: List<exclude> = listOf())

fun KotlinDependencyHandler.installModules(vararg modules: module) = installModules(modules.toList())
fun KotlinDependencyHandler.installModules(modules: List<module>) = modules.forEach {
    api(it.dependency) {
        it.excludes.forEach {
            exclude(it.group, it.module)
        }
    }
}
