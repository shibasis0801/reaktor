package dev.shibasis.reaktor.devtools

import kotlinx.coroutines.CompletableDeferred

/**
 * Browser and worker runtimes.
 *
 * A page cannot listen on a socket, so the listen-and-be-dialled model that Android and Apple use
 * does not apply here: a browser agent connects *out* to the workbench over a WebSocket. That is a
 * different attachment shape rather than a missing feature, and until it is wired the transport
 * says so instead of pretending to accept connections.
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

actual fun devToolsTransport(port: Int): AgentTransport = WebDialOutTransport

private object WebDialOutTransport : AgentTransport {
    override val description: String =
        "web agents dial the workbench; there is no listening socket in a browser"

    /** Never completes: there is nothing to accept. Callers see a target that never attaches. */
    override suspend fun accept(): PeerChannel = CompletableDeferred<PeerChannel>().await()

    override fun close() = Unit
}
