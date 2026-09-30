package dev.shibasis.reaktor.devtools

import kotlin.experimental.ExperimentalNativeApi
import platform.Foundation.NSDate
import platform.Foundation.NSLog
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice

/**
 * Apple.
 *
 * A simulator shares the host's loopback, so the workbench reaches the agent with no forwarding at
 * all. A physical device needs usbmux forwarding, which `idb forward` and `devicectl` both provide
 * — there is no equivalent of `adb`'s abstract sockets to bind to instead.
 */
@OptIn(ExperimentalNativeApi::class)
private object DarwinPlatformInfo : DevToolsPlatformInfo {
    override val name: String = "Darwin"
    override val osVersion: String =
        UIDevice.currentDevice.systemName + " " + UIDevice.currentDevice.systemVersion
    override val deviceModel: String = UIDevice.currentDevice.model
    override val frameVitalsFidelity: Fidelity = Fidelity.Stream
    override val memoryVitalsFidelity: Fidelity = Fidelity.Stream

    override fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    override fun mirrorLog(level: LogLevel, subsystem: String, message: String) {
        NSLog("[%s] %s: %s", level.name, subsystem, message)
    }
}

actual fun devToolsPlatformInfo(): DevToolsPlatformInfo = DarwinPlatformInfo

actual fun devToolsTransport(port: Int): AgentTransport = TcpAgentTransport(port)
