package dev.shibasis.dependeasy.common

import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import dev.shibasis.dependeasy.Version

fun KotlinDependencyHandler.commonSerialization(serializationVersion: String = Version.Serialization, protobuf: Boolean = true) {
    api("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    if (protobuf)
        api("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:$serializationVersion")
}


fun KotlinDependencyHandler.commonCoroutines(coroutinesVersion: String = Version.Coroutines) {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
}


fun KotlinDependencyHandler.commonNetworking() {
    api("io.ktor:ktor-client-core:${Version.Ktor}")
    api("io.ktor:ktor-client-content-negotiation:${Version.Ktor}")
    api("io.ktor:ktor-serialization-kotlinx-json:${Version.Ktor}")
}

fun KotlinDependencyHandler.commonLogging() {
    api("co.touchlab:kermit:${Version.Kermit}")
    api("io.ktor:ktor-client-logging:${Version.Ktor}")
}
