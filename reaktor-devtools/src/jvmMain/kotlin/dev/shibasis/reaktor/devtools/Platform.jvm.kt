package dev.shibasis.reaktor.devtools

/**
 * Desktop. The workbench and the app under inspection can be the same machine here, which is why
 * the transport is plain loopback and needs no forwarding at all.
 */
private object JvmPlatformInfo : DevToolsPlatformInfo {
    override val name: String = "Desktop"
    override val osVersion: String =
        "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
    override val deviceModel: String = System.getProperty("os.arch") ?: "jvm"
    override val frameVitalsFidelity: Fidelity = Fidelity.Static
    override val memoryVitalsFidelity: Fidelity = Fidelity.Stream

    override fun epochMillis(): Long = System.currentTimeMillis()

    override fun mirrorLog(level: LogLevel, subsystem: String, message: String) {
        val stream = if (level.ordinal >= LogLevel.Warn.ordinal) System.err else System.out
        stream.println("[$level] $subsystem: $message")
    }
}

actual fun devToolsPlatformInfo(): DevToolsPlatformInfo = JvmPlatformInfo

actual fun devToolsTransport(port: Int): AgentTransport = TcpAgentTransport(port)
