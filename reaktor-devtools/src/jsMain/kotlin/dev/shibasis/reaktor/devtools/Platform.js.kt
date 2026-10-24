package dev.shibasis.reaktor.devtools

/**
 * Browser and worker runtimes.
 *
 * A page cannot listen on a socket, so the listen-and-be-dialled model that Android and Apple use
 * does not apply here: a browser agent connects *out* to the workbench over a WebSocket.
 */
private object WebPlatformInfo : DevToolsPlatformInfo {
    override val name: String = "Browser"
    override val osVersion: String = "web"
    override val deviceModel: String = "web"
    override val frameVitalsFidelity: Fidelity = Fidelity.Static
    override val memoryVitalsFidelity: Fidelity = Fidelity.Static

    override fun epochMillis(): Long = kotlin.js.Date.now().toLong()

    override fun mirrorLog(level: LogLevel, subsystem: String, message: String) {
        println("[$level] $subsystem: $message")
    }
}

actual fun devToolsPlatformInfo(): DevToolsPlatformInfo = WebPlatformInfo

actual fun devToolsTransport(port: Int): AgentTransport =
    BrowserDialTransport(if (port == DevToolsProtocol.DefaultLoopbackPort) DevToolsProtocol.BrowserPort else port)
