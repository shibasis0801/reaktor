package dev.shibasis.dependeasy.common

import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.dependencies.atVersion

fun KotlinDependencyHandler.apiReaktor(vararg modules: String, version: String? = null) {
    val suffix = version?.let { ":$it" }.orEmpty()
    modules.forEach { api("dev.shibasis:reaktor-$it$suffix") }
}

fun KotlinDependencyHandler.commonSerialization(serializationVersion: String = Versions.Serialization, protobuf: Boolean = true) {
    api(Versions.Kotlin.SerializationCore.atVersion(serializationVersion))
    api(Versions.Kotlin.SerializationJson.atVersion(serializationVersion))
    if (protobuf)
        api(Versions.Kotlin.SerializationProtobuf.atVersion(serializationVersion))
}


fun KotlinDependencyHandler.commonCoroutines(coroutinesVersion: String = Versions.Coroutines) {
    api(Versions.Kotlin.CoroutinesCore.atVersion(coroutinesVersion))
}


fun KotlinDependencyHandler.commonNetworking() {
    api(Versions.Kotlin.KtorCore)
    api(Versions.Kotlin.KtorContentNegotiation)
    api(Versions.Kotlin.KtorJson)
}

fun KotlinDependencyHandler.commonLogging() {
    api(Versions.Kotlin.Kermit)
}

fun KotlinDependencyHandler.arrow() {
    api(project.dependencies.platform(Versions.Kotlin.ArrowBom))
    api(Versions.Kotlin.ArrowCore)
    api(Versions.Kotlin.ArrowCoroutines)
    api(Versions.Kotlin.ArrowResilience)
}
