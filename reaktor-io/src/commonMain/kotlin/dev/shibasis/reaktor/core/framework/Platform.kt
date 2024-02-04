package dev.shibasis.reaktor.core.framework

enum class PlatformType {
    ANDROID, DARWIN, WEB, WORKER, JVM
}
// JS can be Browser/Worker
// JVM can be Desktop/Server

internal expect val __PLATFORM: PlatformType
object Platform {
    val name = __PLATFORM
    fun isAndroid() = name == PlatformType.ANDROID
    fun isDarwin() = name == PlatformType.DARWIN
    fun isWeb() = name == PlatformType.WEB
    fun isWorker() = name == PlatformType.WORKER
    fun isJvm() = name == PlatformType.JVM
}