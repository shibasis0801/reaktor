package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.Version
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler

val kotlinWrapper: (String) -> String = { target -> "org.jetbrains.kotlin-wrappers:kotlin-$target" }

fun KotlinDependencyHandler.kotlinWrappers() {
    api(project.dependencies.platform("org.jetbrains.kotlin-wrappers:kotlin-wrappers-bom:${Version.KotlinJSWrappers}"))
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
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core-js:${Version.Coroutines}")
}

fun KotlinDependencyHandler.webNetworking() {
    api("io.ktor:ktor-client-js:${Version.Ktor}")
}

