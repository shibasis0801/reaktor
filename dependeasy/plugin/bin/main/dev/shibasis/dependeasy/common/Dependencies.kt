package dev.shibasis.dependeasy.common

import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import dev.shibasis.dependeasy.Version
import java.lang.Runtime.Version

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
    implementation("io.ktor:ktor-client-content-negotiation:${Version.Ktor}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${Version.Ktor}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${Version.Serialization}")
}

fun KotlinDependencyHandler.commonLogging() {
    api("co.touchlab:kermit:${Version.Kermit}")
}
