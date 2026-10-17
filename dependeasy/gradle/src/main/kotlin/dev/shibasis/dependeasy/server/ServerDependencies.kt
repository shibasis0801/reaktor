
package dev.shibasis.dependeasy.server

import dev.shibasis.dependeasy.Versions
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler

fun KotlinDependencyHandler.serverNetworking() {
    api(Versions.Kotlin.KtorOkHttp)
}

fun KotlinDependencyHandler.springWebFlux() {
    api(Versions.Server.WebFlux)
}

fun KotlinDependencyHandler.serverCoroutines() {
    api(Versions.Kotlin.CoroutinesSwing)
}
