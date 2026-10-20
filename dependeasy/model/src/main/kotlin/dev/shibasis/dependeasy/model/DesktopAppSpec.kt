package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
enum class Installer { Dmg, Msi, Deb }
operator fun Installer.plus(other: Installer): Set<Installer> = setOf(this, other)

@Serializable
enum class JdkModule(val moduleName: String) {
    Sql("java.sql"), Http("java.net.http"), Naming("java.naming"), Management("java.management"),
    Security("java.security.jgss"), Crypto("jdk.crypto.ec"), HttpServer("jdk.httpserver"), FlightRecorder("jdk.jfr"),
}

@Serializable
@JvmInline value class Memory(val megabytes: Int) {
    init { require(megabytes > 0) { "Memory must be positive" } }
}
val Int.megabytes get() = Memory(this)

@Serializable
data class DesktopRuntime(val initialHeap: Memory? = null, val properties: Map<String, String> = emptyMap())

@Serializable
data class DesktopAppSpec(
    val packageName: String,
    val installers: Set<Installer>,
    val version: String = "1.0.0",
    val modules: Set<JdkModule> = emptySet(),
    val outputDirectory: String? = null,
    val runtime: DesktopRuntime = DesktopRuntime(),
    val run: Run = Run.onDesktopSession(),
) {
    init {
        require(packageName.isNotBlank() && version.isNotBlank()) { "A desktop app needs its package name and version" }
        require(installers.isNotEmpty()) { "A desktop app needs its installers" }
    }
}
