package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
data class DaggerToolchainContract(
    val schemaVersion: Int = 2,
    val javaImage: String,
    val browserImage: String,
    val gradleUrl: String,
    val gradleSha256: String,
    val gradleArchive: String,
    val androidSdk: AndroidSdkContract,
    val packages: List<String> = emptyList(),
    val caches: Map<String, String> = emptyMap(),
    val memory: ContainerMemory = ContainerMemory(),
    val files: Map<String, String> = emptyMap(),
)

@Serializable
data class ContainerMemory(
    val gradle: JvmMemory = JvmMemory(768, 768, 96),
    val kotlin: JvmMemory = JvmMemory(2560, 384, 96),
    val commonizer: JvmMemory = JvmMemory(512, 128, 32),
    val maxWorkers: Int = 1,
    val nodeMiB: Int = 384,
) {
    internal fun gradleProperties(): String = listOf(
        "org.gradle.daemon=false", "org.gradle.jvmargs=${gradle.arguments()} -Dkotlin.daemon.options=autoshutdownIdleSeconds=5",
        "org.gradle.workers.max=$maxWorkers", "org.gradle.parallel=false", "org.gradle.vfs.watch=false",
        "kotlin.compiler.execution.strategy=daemon", "kotlin.daemon.useFallbackStrategy=false",
        "kotlin.daemon.jvmargs=${kotlin.arguments()}", "kotlin.mpp.commonizerJvmArgs=${commonizer.arguments()}",
    ).joinToString("\n", postfix = "\n")
}

@Serializable
data class JvmMemory(val heapMiB: Int, val metaspaceMiB: Int, val codeCacheMiB: Int) {
    internal fun arguments(): String = "-Xmx${heapMiB}m -XX:NewRatio=5 -XX:MaxMetaspaceSize=${metaspaceMiB}m " +
        "-XX:ReservedCodeCacheSize=${codeCacheMiB}m -XX:+UseSerialGC"
}

@Serializable
data class AndroidSdkContract(
    val directory: String,
    val commandLineTools: String,
    val downloadUrl: String,
    val sha256: String,
    val packages: List<String>,
)
