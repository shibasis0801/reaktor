package dev.shibasis.dependeasy.server

import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.utils.module
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler

fun KotlinDependencyHandler.vertx() {
    api(platform("io.vertx:vertx-stack-depchain:${Version.SDK.Vertx}"))
    api("io.vertx:vertx-core")
    api("io.vertx:vertx-web")
    api("io.vertx:vertx-auth")
    api("io.vertx:vertx-shell")
    api("io.vertx:vertx-lang-kotlin")
    api("io.vertx:vertx-lang-kotlin-coroutines")
    api("io.vertx:vertx-web-graphql")
    api("io.vertx:vertx-pg-client")
    api("io.vertx:vertx-grpc-server")
    api("io.ktor:ktor-client-java:${Version.Ktor}")
}

fun KotlinDependencyHandler.serverNetworking() {
    api("io.ktor:ktor-client-okhttp:${Version.Ktor}")
}