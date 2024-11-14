package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.Version
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler

val kotlinWrapper: (String) -> String = { target -> "org.jetbrains.kotlin-wrappers:kotlin-$target" }

fun KotlinDependencyHandler.kotlinWrappers() {
    api(project.dependencies.enforcedPlatform("org.jetbrains.kotlin-wrappers:kotlin-wrappers-bom:${Version.KotlinJSWrappers}"))
    api(kotlinWrapper("js"))
    api(kotlinWrapper("browser"))
    api(kotlinWrapper("web"))
    api(kotlinWrapper("typescript"))
}

fun KotlinDependencyHandler.react() {
    api(kotlinWrapper("js"))
    api(kotlinWrapper("browser"))
    api(kotlinWrapper("web"))
    api(kotlinWrapper("typescript"))
    api(kotlinWrapper("emotion"))
    api(kotlinWrapper("react"))
    api(kotlinWrapper("react-dom"))
    api(kotlinWrapper("react-router-dom"))
    api(kotlinWrapper("mui-material"))
    api(kotlinWrapper("mui-base"))
    api(kotlinWrapper("mui-icons-material"))
    api(kotlinWrapper("mui-lab"))
    api(kotlinWrapper("mui-system"))
    api(kotlinWrapper("muix-date-pickers"))
    api(kotlinWrapper("muix-tree-view"))
}

fun KotlinDependencyHandler.webCoroutines() {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core-js:${Version.Coroutines}")
}

fun KotlinDependencyHandler.webNetworking() {
    api("io.ktor:ktor-client-js:${Version.Ktor}")
}

