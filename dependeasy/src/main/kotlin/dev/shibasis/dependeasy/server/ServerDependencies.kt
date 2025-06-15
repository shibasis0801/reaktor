
package dev.shibasis.dependeasy.server

import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.utils.module
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler

fun KotlinDependencyHandler.serverNetworking() {
    api("io.ktor:ktor-client-okhttp:${Version.Ktor}")
}

fun KotlinDependencyHandler.springWebFlux() {
    api("org.springframework.boot:spring-boot-starter-webflux:${Version.SDK.Spring}")
}