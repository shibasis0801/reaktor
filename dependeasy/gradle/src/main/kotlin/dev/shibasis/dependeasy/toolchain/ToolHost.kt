package dev.shibasis.dependeasy.toolchain

import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.model.BuildOption
import org.gradle.api.Project

internal data class BuildHost(val system: String, val architecture: String) {
    val mac = system.contains("mac", ignoreCase = true)
    val windows = system.startsWith("Windows", ignoreCase = true)
    val linux = system.contains("linux", ignoreCase = true)
    val arm = when (architecture) {
        "aarch64", "arm64" -> true
        "amd64", "x86_64" -> false
        else -> error("Unsupported Dependeasy host architecture: $architecture")
    }
    fun toolPlatform(): String {
        require(mac || linux) { "Dependeasy tools require macOS or Linux" }
        return "${if (mac) "darwin" else "linux"}-${if (arm) "arm64" else "x64"}"
    }
}

internal val Project.buildHost get() = BuildHost(option(BuildOption.systemProperty("os.name")).get(),
    option(BuildOption.systemProperty("os.arch")).get())

internal fun Project.toolPlatform() = buildHost.toolPlatform()
