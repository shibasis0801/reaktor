package dev.shibasis.dependeasy.toolchain

internal fun toolPlatform(): String {
    val system = when {
        System.getProperty("os.name").contains("Mac") -> "darwin"
        System.getProperty("os.name").contains("Linux") -> "linux"
        else -> error("Dependeasy tools require macOS or Linux")
    }
    val architecture = when (System.getProperty("os.arch")) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "x64"
        else -> error("Unsupported Dependeasy tool architecture")
    }
    return "$system-$architecture"
}
