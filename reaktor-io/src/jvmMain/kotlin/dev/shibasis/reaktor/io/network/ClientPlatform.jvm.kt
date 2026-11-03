package dev.shibasis.reaktor.io.network

actual object ClientPlatform {
    actual val os: String = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
    actual val device: String = System.getProperty("os.arch").orEmpty()
}
