package dev.shibasis.dependeasy.common

import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import dev.shibasis.dependeasy.Version

fun KotlinDependencyHandler.commonSerialization(serializationVersion: String = Version.Serialization, protobuf: Boolean = false) {
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
}

fun KotlinDependencyHandler.basic() {
    commonKoin()
    commonNetworking()
    commonCoroutines()
    commonSerialization()
}