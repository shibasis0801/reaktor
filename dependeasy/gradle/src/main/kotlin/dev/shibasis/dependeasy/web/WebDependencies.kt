package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.Versions
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler

val kotlinWrapper: (String) -> String = Versions.Kotlin::wrapper

fun KotlinDependencyHandler.kotlinWrappers() {
    api(project.dependencies.platform(Versions.Kotlin.WrappersBom))
    api(kotlinWrapper("js"))
    api(kotlinWrapper("browser"))
    api(kotlinWrapper("web"))
    api(kotlinWrapper("typescript"))
}

fun KotlinDependencyHandler.react() {
    api(kotlinWrapper("css"))
    api(kotlinWrapper("typescript"))
    api(kotlinWrapper("emotion-react"))
    api(kotlinWrapper("react"))
    api(kotlinWrapper("react-use"))
    api(kotlinWrapper("react-dom"))
    api(kotlinWrapper("react-router"))
}

fun KotlinDependencyHandler.webCoroutines() {
    api(Versions.Kotlin.CoroutinesJs)
}

fun KotlinDependencyHandler.webNetworking() {
    api(Versions.Kotlin.KtorJs)
}

